#!/bin/bash

set -euo pipefail

APP_NAME="ktb-chat-frontend"
CONTAINER_NAME="${CONTAINER_NAME:-ktb-frontend}"
PREV_CONTAINER_NAME="${CONTAINER_NAME}-prev"
PORT_HTTP="${PORT_HTTP:-3000}"
HEALTH_CHECK_URL="${HEALTH_CHECK_URL:-http://localhost:${PORT_HTTP}/}"
HEALTH_CHECK_TIMEOUT="${HEALTH_CHECK_TIMEOUT:-60}"
HEALTH_CHECK_INTERVAL="${HEALTH_CHECK_INTERVAL:-2}"
STATE_FILE="${STATE_FILE:-$HOME/ktb-chat-frontend/.current-image}"
PREVIOUS_STATE_FILE="${PREVIOUS_STATE_FILE:-$HOME/ktb-chat-frontend/.previous-image}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

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
    docker run -d \
        --name "$CONTAINER_NAME" \
        --restart unless-stopped \
        -p "${PORT_HTTP}:${PORT_HTTP}" \
        --log-opt max-size=50m --log-opt max-file=3 \
        "$image_ref" > /dev/null
}

deploy() {
    local image_ref="${IMAGE_REF:-${1:-}}"
    local previous_image=""
    if [ -z "$image_ref" ]; then
        log_error "IMAGE_REF is required (env var or first argument), e.g. ghcr.io/org/repo-frontend:sha"
        exit 1
    fi

    log_info "Pulling $image_ref..."
    docker pull "$image_ref"

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
        mkdir -p "$(dirname "$STATE_FILE")"
        echo "$image_ref" > "$STATE_FILE"
        if [ "$had_previous" = true ]; then
            echo "$previous_image" > "$PREVIOUS_STATE_FILE"
            docker rm -f "$PREV_CONTAINER_NAME" > /dev/null
        else
            rm -f "$PREVIOUS_STATE_FILE"
        fi
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
        echo "  IMAGE_REF, CONTAINER_NAME, PORT_HTTP, STATE_FILE, PREVIOUS_STATE_FILE"
        exit 1
        ;;
esac
