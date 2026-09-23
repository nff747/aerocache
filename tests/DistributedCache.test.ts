import { describe, it, expect } from 'vitest';
import { DistributedCache } from '../src/DistributedCache';
import { CacheNode } from '../src/CacheNode';

describe('DistributedCache', () => {
    it('should set and get values across multiple nodes', () => {
        const cache = new DistributedCache();
        cache.addNode(new CacheNode('node-1'));
        cache.addNode(new CacheNode('node-2'));

        cache.set('user:1', 'Alice', 1000);
        cache.set('user:2', 'Bob', 1000);

        expect(cache.get('user:1')).toBe('Alice');
        expect(cache.get('user:2')).toBe('Bob');
    });

    it('should return null for missing keys', () => {
        const cache = new DistributedCache();
        cache.addNode(new CacheNode('node-1'));

        expect(cache.get('missing-key')).toBeNull();
    });
});
