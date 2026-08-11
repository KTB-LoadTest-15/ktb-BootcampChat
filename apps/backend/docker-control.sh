#!/bin/bash

###############################################################################
# KTB Chat Backend — Docker container control script
#
# Replaces app-control.sh (systemd + bare JAR) now that CD ships a container
# image via GHCR instead of rsyncing a JAR. Docker's own --restart policy
# handles crash/reboot recovery, so this script only needs to manage the
# pull -> swap -> health-check -> (rollback on failure) cycle.
#
# A host only has room to run one container on the app ports at a time, so a
# deploy is a brief stop-the-old/start-the-new swap, not a zero-downtime
# blue/green cutover. The previous container is kept (stopped, not removed)
# until the new one passes its health check, so a bad deploy rolls back
# automatically without a re-pull.
#
# Usage:
#   IMAGE_REF=ghcr.io/org/ktb-bootcampchat-backend:sha ./docker-control.sh deploy
#   ./docker-control.sh rollback
#   ./docker-control.sh status
#   ./docker-control.sh stop
###############################################################################

set -euo pipefail

APP_NAME="ktb-chat-backend"
CONTAINER_NAME="${CONTAINER_NAME:-ktb-backend}"
PREV_CONTAINER_NAME="${CONTAINER_NAME}-prev"
ENV_FILE="${ENV_FILE:-$HOME/ktb-chat-backend/.env}"
PORT_HTTP="${PORT_HTTP:-5001}"
PORT_WS="${PORT_WS:-5002}"
HEALTH_CHECK_URL="${HEALTH_CHECK_URL:-http://localhost:${PORT_HTTP}/api/health}"
HEALTH_CHECK_TIMEOUT="${HEALTH_CHECK_TIMEOUT:-60}"
HEALTH_CHECK_INTERVAL="${HEALTH_CHECK_INTERVAL:-2}"
STATE_FILE="${STATE_FILE:-$HOME/ktb-chat-backend/.current-image}"
PREVIOUS_STATE_FILE="${PREVIOUS_STATE_FILE:-$HOME/ktb-chat-backend/.previous-image}"
MIN_FREE_KB="${MIN_FREE_KB:-1048576}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

current_image_ref() {
    if container_exists "$CONTAINER_NAME"; then
        docker inspect -f '{{.Config.Image}}' "$CONTAINER_NAME" 2>/dev/null || true
    fi
}

previous_image_ref() {
    if container_exists "$PREV_CONTAINER_NAME"; then
        docker inspect -f '{{.Config.Image}}' "$PREV_CONTAINER_NAME" 2>/dev/null || true
    elif [ -f "$PREVIOUS_STATE_FILE" ]; then
        cat "$PREVIOUS_STATE_FILE"
    fi
}

free_space_kb() {
    df -Pk "${DOCKER_ROOT_CHECK_PATH:-/var/lib/docker}" 2>/dev/null | awk 'NR==2 {print $4}'
}

log_disk_usage() {
    local free_kb
    free_kb="$(free_space_kb)"
    if [ -n "${free_kb:-}" ]; then
        log_info "Free space near Docker root: $((free_kb / 1024)) MB"
    fi
    docker system df || true
}

remove_stale_app_images() {
    local target_image="${1:-}"
    local keep_refs
    local current_ref
    local previous_ref
    local image_id
    local refs
    local ref

    keep_refs=" $target_image "
    current_ref="$(current_image_ref)"
    previous_ref="$(previous_image_ref)"

    if [ -n "${current_ref:-}" ]; then
        keep_refs="${keep_refs}${current_ref} "
    fi
    if [ -n "${previous_ref:-}" ]; then
        keep_refs="${keep_refs}${previous_ref} "
    fi

    while IFS= read -r image_id; do
        [ -n "$image_id" ] || continue
        refs="$(docker image inspect -f '{{range .RepoTags}}{{println .}}{{end}}' "$image_id" 2>/dev/null || true)"
        [ -n "$refs" ] || continue

        for ref in $refs; do
            case "$ref" in
                ghcr.io/ktb-loadtest-15/ktb-bootcampchat-backend:*)
                    if [[ "$keep_refs" == *" $ref "* ]]; then
                        continue
                    fi
                    log_info "Removing stale image $ref"
                    docker image rm -f "$ref" >/dev/null || true
                    break
                    ;;
            esac
        done
    done < <(docker images -q --no-trunc | sort -u)
}

remove_all_but_one_existing_app_image() {
    local keep_ref=""
    local current_ref
    local previous_ref
    local image_id
    local refs
    local ref

    current_ref="$(current_image_ref)"
    previous_ref="$(previous_image_ref)"

    if [ -n "${current_ref:-}" ]; then
        keep_ref="$current_ref"
    elif [ -n "${previous_ref:-}" ]; then
        keep_ref="$previous_ref"
    fi

    if [ -n "$keep_ref" ]; then
        log_info "Keeping one existing backend image before pull: $keep_ref"
    else
        log_info "No active backend image to preserve before pull"
    fi

    while IFS= read -r image_id; do
        [ -n "$image_id" ] || continue
        refs="$(docker image inspect -f '{{range .RepoTags}}{{println .}}{{end}}' "$image_id" 2>/dev/null || true)"
        [ -n "$refs" ] || continue

        for ref in $refs; do
            case "$ref" in
                ghcr.io/ktb-loadtest-15/ktb-bootcampchat-backend:*)
                    if [ -n "$keep_ref" ] && [ "$ref" = "$keep_ref" ]; then
                        continue
                    fi
                    log_info "Removing existing image before pull: $ref"
                    docker image rm -f "$ref" >/dev/null || true
                    break
                    ;;
            esac
        done
    done < <(docker images -q --no-trunc | sort -u)
}

ensure_disk_headroom() {
    local target_image="${1:-}"
    local before_kb
    local after_kb

    before_kb="$(free_space_kb)"
    if [ -n "${before_kb:-}" ] && [ "$before_kb" -ge "$MIN_FREE_KB" ]; then
        return 0
    fi

    log_warn "Low disk space detected before deploy. Starting targeted Docker cleanup..."
    log_disk_usage
    docker container prune -f >/dev/null || true
    docker image prune -f >/dev/null || true
    remove_all_but_one_existing_app_image
    remove_stale_app_images "$target_image"
    after_kb="$(free_space_kb)"
    log_disk_usage

    if [ -z "${after_kb:-}" ] || [ "$after_kb" -lt "$MIN_FREE_KB" ]; then
        log_error "Insufficient free space for deploy after cleanup. Required: $((MIN_FREE_KB / 1024)) MB, available: $(( ${after_kb:-0} / 1024 )) MB"
        exit 1
    fi
}

check_env_file() {
    if [ ! -f "$ENV_FILE" ]; then
        log_error "env file not found: $ENV_FILE"
        log_info "It must be written to this host before deploy (see CD workflow / docs/CI_CD.md)"
        exit 1
    fi
}

container_exists() {
    docker inspect "$1" >/dev/null 2>&1
}

container_running() {
    [ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null || echo false)" = "true" ]
}

wait_for_health() {
    log_info "Waiting for application to be healthy..."
    local elapsed=0
    while [ "$elapsed" -lt "$HEALTH_CHECK_TIMEOUT" ]; do
        if curl -sf "$HEALTH_CHECK_URL" > /dev/null 2>&1; then
            log_success "Application is healthy!"
            return 0
        fi
        if ! container_running "$CONTAINER_NAME"; then
            echo ""
            log_error "Container exited while starting up"
            log_info "Logs: docker logs --tail 100 $CONTAINER_NAME"
            return 1
        fi
        sleep "$HEALTH_CHECK_INTERVAL"
        elapsed=$((elapsed + HEALTH_CHECK_INTERVAL))
        echo -n "."
    done
    echo ""
    log_error "Health check timeout after ${HEALTH_CHECK_TIMEOUT}s"
    return 1
}

run_new_container() {
    local image_ref="$1"
    log_info "Starting $CONTAINER_NAME from $image_ref..."
    # application.properties no longer sets Mongo/Redis defaults itself —
    # those now live in application-{dev,local,prod}.properties, so a
    # container started without an active profile fails to boot (no
    # MONGO_URI/REDIS_HOST placeholder to resolve at all). SPRING_PROFILES_ACTIVE
    # is a deploy-target constant, not a secret, so it's set here rather than
    # sourced from the .env file.
    docker run -d \
        --name "$CONTAINER_NAME" \
        --restart unless-stopped \
        -p "${PORT_HTTP}:${PORT_HTTP}" \
        -p "${PORT_WS}:${PORT_WS}" \
        --env-file "$ENV_FILE" \
        -e SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-prod}" \
        --log-opt max-size=50m --log-opt max-file=3 \
        "$image_ref" > /dev/null
}

deploy() {
    local image_ref="${IMAGE_REF:-${1:-}}"
    local previous_image=""
    if [ -z "$image_ref" ]; then
        log_error "IMAGE_REF is required (env var or first argument), e.g. ghcr.io/org/repo-backend:sha"
        exit 1
    fi
    check_env_file
    remove_all_but_one_existing_app_image
    ensure_disk_headroom "$image_ref"

    log_info "Pulling $image_ref..."
    docker pull "$image_ref"

    # Move the currently running container out of the way (stopped, kept
    # around) instead of removing it, so a failed health check can just be
    # started back up without a re-pull.
    if container_exists "$PREV_CONTAINER_NAME"; then
        log_info "Removing leftover $PREV_CONTAINER_NAME from an earlier deploy..."
        docker rm -f "$PREV_CONTAINER_NAME" > /dev/null
    fi
    local had_previous=false
    if container_exists "$CONTAINER_NAME"; then
        had_previous=true
        previous_image="$(docker inspect -f '{{.Config.Image}}' "$CONTAINER_NAME")"
        log_info "Stopping current $CONTAINER_NAME and keeping it as $PREV_CONTAINER_NAME for rollback..."
        docker stop "$CONTAINER_NAME" > /dev/null
        docker rename "$CONTAINER_NAME" "$PREV_CONTAINER_NAME"
    fi

    run_new_container "$image_ref"

    if wait_for_health; then
        echo "$image_ref" > "$STATE_FILE"
        if [ "$had_previous" = true ]; then
            echo "$previous_image" > "$PREVIOUS_STATE_FILE"
            docker rm -f "$PREV_CONTAINER_NAME" > /dev/null
        else
            rm -f "$PREVIOUS_STATE_FILE"
        fi
        remove_stale_app_images "$image_ref"
        log_success "Deploy complete: $image_ref"
        return 0
    fi

    log_error "New container failed health check — rolling back"
    docker logs --tail 100 "$CONTAINER_NAME" || true
    docker rm -f "$CONTAINER_NAME" > /dev/null || true

    if [ "$had_previous" = true ]; then
        docker rename "$PREV_CONTAINER_NAME" "$CONTAINER_NAME"
        docker start "$CONTAINER_NAME" > /dev/null
        log_warn "Rolled back to previous container"
    else
        log_warn "No previous container to roll back to — host is now down"
    fi
    exit 1
}

# Re-run the previously recorded good image, independent of any CI run.
rollback() {
    if [ ! -f "$PREVIOUS_STATE_FILE" ]; then
        log_error "No recorded rollback image at $PREVIOUS_STATE_FILE"
        exit 1
    fi
    local prev_image
    prev_image="$(cat "$PREVIOUS_STATE_FILE")"
    log_warn "Rolling back to $prev_image"
    IMAGE_REF="$prev_image" deploy
}

stop() {
    if ! container_exists "$CONTAINER_NAME"; then
        log_warn "$APP_NAME is not deployed"
        return 0
    fi
    docker stop "$CONTAINER_NAME" > /dev/null
    log_success "$APP_NAME stopped"
}

status() {
    if ! container_exists "$CONTAINER_NAME"; then
        log_warn "$CONTAINER_NAME does not exist on this host"
        return 0
    fi
    echo ""
    docker ps -a --filter "name=^${CONTAINER_NAME}$" --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'
    echo ""
    if container_running "$CONTAINER_NAME"; then
        if curl -sf "$HEALTH_CHECK_URL" > /dev/null 2>&1; then
            log_success "Health check passed"
        else
            log_warn "Running but health check failed"
        fi
    fi
    echo ""
    echo "  Logs: docker logs -f $CONTAINER_NAME"
}

case "${1:-}" in
    deploy)
        shift || true
        deploy "$@"
        ;;
    rollback)
        rollback
        ;;
    stop)
        stop
        ;;
    status)
        status
        ;;
    *)
        echo "Usage: $0 {deploy <image_ref>|rollback|stop|status}"
        echo ""
        echo "Environment overrides:"
        echo "  IMAGE_REF, CONTAINER_NAME, ENV_FILE, PORT_HTTP, PORT_WS, STATE_FILE, PREVIOUS_STATE_FILE"
        exit 1
        ;;
esac
