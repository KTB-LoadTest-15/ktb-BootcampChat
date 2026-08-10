import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

export const MESSAGE_VIRTUALIZATION_THRESHOLD = 100;
export const ESTIMATED_MESSAGE_HEIGHT = 176;
export const MESSAGE_OVERSCAN = 3;

export const buildVirtualLayout = ({
  items,
  getItemKey,
  measuredSizes,
  estimatedSize,
}) => {
  const offsets = new Array(items.length);
  const sizes = new Array(items.length);
  let totalSize = 0;

  items.forEach((item, index) => {
    const key = getItemKey(item, index);
    const size = measuredSizes.get(key) || estimatedSize;
    offsets[index] = totalSize;
    sizes[index] = size;
    totalSize += size;
  });

  return { offsets, sizes, totalSize };
};

export const calculateVirtualRange = ({
  offsets,
  sizes,
  scrollTop,
  viewportHeight,
  overscan,
}) => {
  if (offsets.length === 0) {
    return { startIndex: 0, endIndex: 0 };
  }

  let low = 0;
  let high = offsets.length - 1;
  let firstVisible = 0;

  while (low <= high) {
    const middle = Math.floor((low + high) / 2);
    const itemEnd = offsets[middle] + sizes[middle];

    if (itemEnd < scrollTop) {
      low = middle + 1;
    } else {
      firstVisible = middle;
      high = middle - 1;
    }
  }

  const viewportEnd = scrollTop + Math.max(viewportHeight, sizes[firstVisible]);
  let lastVisible = firstVisible;

  while (
    lastVisible < offsets.length - 1 &&
    offsets[lastVisible] < viewportEnd
  ) {
    lastVisible += 1;
  }

  return {
    startIndex: Math.max(0, firstVisible - overscan),
    endIndex: Math.min(offsets.length, lastVisible + overscan + 1),
  };
};

export const useVirtualMessageList = ({
  items,
  containerRef,
  getItemKey,
  threshold = MESSAGE_VIRTUALIZATION_THRESHOLD,
  estimatedSize = ESTIMATED_MESSAGE_HEIGHT,
  overscan = MESSAGE_OVERSCAN,
}) => {
  const listRef = useRef(null);
  const [measuredSizes, setMeasuredSizes] = useState(() => new Map());
  const [viewport, setViewport] = useState({ scrollTop: 0, height: 0 });
  const isVirtualized = items.length > threshold;

  useEffect(() => {
    if (!isVirtualized) return;

    const container = containerRef.current;
    if (!container) return;

    let frameId = null;
    const updateViewport = () => {
      frameId = null;
      const listTop = listRef.current?.offsetTop || 0;
      setViewport({
        scrollTop: Math.max(0, container.scrollTop - listTop),
        height: container.clientHeight,
      });
    };
    const scheduleViewportUpdate = () => {
      if (frameId === null) {
        frameId = requestAnimationFrame(updateViewport);
      }
    };

    updateViewport();
    container.addEventListener('scroll', scheduleViewportUpdate, { passive: true });
    const resizeObserver = typeof ResizeObserver === 'undefined'
      ? null
      : new ResizeObserver(scheduleViewportUpdate);
    resizeObserver?.observe(container);

    return () => {
      container.removeEventListener('scroll', scheduleViewportUpdate);
      resizeObserver?.disconnect();
      if (frameId !== null) {
        cancelAnimationFrame(frameId);
      }
    };
  }, [containerRef, isVirtualized]);

  const layout = useMemo(() => buildVirtualLayout({
    items,
    getItemKey,
    measuredSizes,
    estimatedSize,
  }), [items, getItemKey, measuredSizes, estimatedSize]);

  const range = useMemo(() => calculateVirtualRange({
    offsets: layout.offsets,
    sizes: layout.sizes,
    scrollTop: viewport.scrollTop,
    viewportHeight: viewport.height,
    overscan,
  }), [layout, overscan, viewport]);

  const measureItems = useCallback((measurements) => {
    setMeasuredSizes(previousSizes => {
      let nextSizes = null;

      for (const { key, size } of measurements) {
        if (!Number.isFinite(size) || size <= 0) continue;

        const previousSize = previousSizes.get(key) || estimatedSize;
        if (Math.abs(previousSize - size) < 1) continue;

        if (!nextSizes) {
          nextSizes = new Map(previousSizes);
        }
        nextSizes.set(key, size);
      }

      return nextSizes || previousSizes;
    });
  }, [estimatedSize]);

  const virtualItems = useMemo(() => {
    if (!isVirtualized) return [];

    return items
      .slice(range.startIndex, range.endIndex)
      .map((item, offset) => {
        const index = range.startIndex + offset;
        return {
          index,
          item,
          key: getItemKey(item, index),
          start: layout.offsets[index],
        };
      });
  }, [getItemKey, isVirtualized, items, layout.offsets, range]);

  return {
    isVirtualized,
    listRef,
    measureItems,
    totalSize: layout.totalSize,
    virtualItems,
  };
};

export default useVirtualMessageList;
