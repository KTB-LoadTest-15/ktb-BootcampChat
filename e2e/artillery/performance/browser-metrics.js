const METRICS_KEY = '__KTB_PERF_METRICS__';

function createMetricState() {
    return {
        enabled: true,
        messageReceivedAt: {},
        messageToDomMs: [],
        messagesReceived: 0,
        messagesDomDisplayed: 0,
        userMessageRenders: {},
        readSentEvents: 0,
        readSentMessageIds: 0,
        readReceivedEvents: 0,
        readReceivedMessageIds: 0,
        longTaskCount: 0,
        longTaskTotalMs: 0,
        longTaskMaxMs: 0,
    };
}

function initializeBrowserMetrics() {
    const metricsKey = '__KTB_PERF_METRICS__';
    window[metricsKey] = {
        enabled: true,
        messageReceivedAt: {},
        messageToDomMs: [],
        messagesReceived: 0,
        messagesDomDisplayed: 0,
        userMessageRenders: {},
        readSentEvents: 0,
        readSentMessageIds: 0,
        readReceivedEvents: 0,
        readReceivedMessageIds: 0,
        longTaskCount: 0,
        longTaskTotalMs: 0,
        longTaskMaxMs: 0,
    };

    if (!PerformanceObserver.supportedEntryTypes?.includes('longtask')) {
        return;
    }

    const observer = new PerformanceObserver((list) => {
        const metrics = window[metricsKey];
        if (!metrics?.enabled) return;

        for (const entry of list.getEntries()) {
            metrics.longTaskCount += 1;
            metrics.longTaskTotalMs += entry.duration;
            metrics.longTaskMaxMs = Math.max(metrics.longTaskMaxMs, entry.duration);
        }
    });

    observer.observe({ type: 'longtask', buffered: true });
    window.__KTB_PERF_LONG_TASK_OBSERVER__ = observer;
}

async function installBrowserMetrics(page) {
    await page.addInitScript(initializeBrowserMetrics);
}

async function resetBrowserMetrics(page) {
    await page.evaluate(({ metricsKey, state }) => {
        const current = window[metricsKey];
        window[metricsKey] = {
            ...state,
            enabled: current?.enabled !== false,
        };
    }, { metricsKey: METRICS_KEY, state: createMetricState() });
}

async function collectBrowserMetrics(page) {
    return page.evaluate(async (metricsKey) => {
        await new Promise((resolve) => {
            requestAnimationFrame(() => requestAnimationFrame(resolve));
        });

        const metrics = window[metricsKey];
        if (!metrics?.enabled) return null;

        const renderCounts = Object.values(metrics.userMessageRenders);
        const totalRenders = renderCounts.reduce((sum, count) => sum + count, 0);
        const rerenders = renderCounts.reduce(
            (sum, count) => sum + Math.max(0, count - 1),
            0
        );

        return {
            messageToDomMs: [...metrics.messageToDomMs],
            messagesReceived: metrics.messagesReceived,
            messagesDomDisplayed: metrics.messagesDomDisplayed,
            readSentEvents: metrics.readSentEvents,
            readSentMessageIds: metrics.readSentMessageIds,
            readReceivedEvents: metrics.readReceivedEvents,
            readReceivedMessageIds: metrics.readReceivedMessageIds,
            longTaskCount: metrics.longTaskCount,
            longTaskTotalMs: metrics.longTaskTotalMs,
            longTaskMaxMs: metrics.longTaskMaxMs,
            domNodes: document.querySelectorAll('*').length,
            messageNodes: document.querySelectorAll('[data-testid="message-container"]').length,
            heapUsedMb: performance.memory?.usedJSHeapSize
                ? performance.memory.usedJSHeapSize / 1024 / 1024
                : null,
            totalRenders,
            distinctRenderedMessages: renderCounts.length,
            rerenders,
            renderCounts,
        };
    }, METRICS_KEY);
}

function emitCounter(events, name, value) {
    if (Number.isFinite(value) && value >= 0) {
        events.emit('counter', name, value);
    }
}

function emitHistogram(events, name, value) {
    if (Number.isFinite(value)) {
        events.emit('histogram', name, value);
    }
}

function emitBrowserMetrics(events, snapshot) {
    if (!events || !snapshot) return;

    for (const duration of snapshot.messageToDomMs) {
        emitHistogram(events, 'chat.message_to_dom_ms', duration);
    }

    for (const renderCount of snapshot.renderCounts) {
        emitHistogram(events, 'chat.user_message.renders_per_message', renderCount);
    }

    emitCounter(events, 'chat.messages.received', snapshot.messagesReceived);
    emitCounter(events, 'chat.messages.dom_displayed', snapshot.messagesDomDisplayed);
    emitCounter(events, 'chat.read.sent_events', snapshot.readSentEvents);
    emitCounter(events, 'chat.read.sent_message_ids', snapshot.readSentMessageIds);
    emitCounter(events, 'chat.read.received_events', snapshot.readReceivedEvents);
    emitCounter(events, 'chat.read.received_message_ids', snapshot.readReceivedMessageIds);
    emitCounter(events, 'browser.long_tasks.count', snapshot.longTaskCount);
    emitCounter(events, 'chat.user_message.renders', snapshot.totalRenders);
    emitCounter(events, 'chat.user_message.rerenders', snapshot.rerenders);

    emitHistogram(events, 'browser.long_tasks.total_ms', snapshot.longTaskTotalMs);
    emitHistogram(events, 'browser.long_tasks.max_ms', snapshot.longTaskMaxMs);
    emitHistogram(events, 'browser.dom.nodes', snapshot.domNodes);
    emitHistogram(events, 'chat.dom.message_nodes', snapshot.messageNodes);
    emitHistogram(events, 'chat.user_message.distinct_messages', snapshot.distinctRenderedMessages);

    if (snapshot.heapUsedMb !== null) {
        emitHistogram(events, 'browser.heap.used_mb', snapshot.heapUsedMb);
    }
}

module.exports = {
    collectBrowserMetrics,
    emitBrowserMetrics,
    initializeBrowserMetrics,
    installBrowserMetrics,
    resetBrowserMetrics,
};
