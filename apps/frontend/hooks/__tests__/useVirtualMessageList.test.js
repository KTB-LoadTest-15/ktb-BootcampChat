import { describe, expect, it } from 'vitest';
import {
  buildVirtualLayout,
  calculateVirtualRange,
} from '../useVirtualMessageList';

describe('useVirtualMessageList helpers', () => {
  it('builds offsets with measured heights and estimates for unknown rows', () => {
    const items = [{ id: 'a' }, { id: 'b' }, { id: 'c' }];
    const layout = buildVirtualLayout({
      items,
      getItemKey: item => item.id,
      measuredSizes: new Map([['b', 240]]),
      estimatedSize: 100,
    });

    expect(layout).toEqual({
      offsets: [0, 100, 340],
      sizes: [100, 240, 100],
      totalSize: 440,
    });
  });

  it('returns only the visible range plus overscan', () => {
    const range = calculateVirtualRange({
      offsets: [0, 100, 200, 300, 400, 500],
      sizes: [100, 100, 100, 100, 100, 100],
      scrollTop: 210,
      viewportHeight: 180,
      overscan: 1,
    });

    expect(range).toEqual({ startIndex: 1, endIndex: 6 });
  });

  it('handles an empty message list', () => {
    expect(calculateVirtualRange({
      offsets: [],
      sizes: [],
      scrollTop: 0,
      viewportHeight: 0,
      overscan: 6,
    })).toEqual({ startIndex: 0, endIndex: 0 });
  });
});
