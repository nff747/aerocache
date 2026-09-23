import { describe, it, expect } from 'vitest';
import { CacheNode } from '../src/CacheNode';

describe('CacheNode', () => {
    it('should store and retrieve data within TTL', () => {
        const node = new CacheNode('node-1');
        node.set('key1', 'value1', 1000);
        expect(node.get('key1')).toBe('value1');
    });

    it('should evict data after TTL', async () => {
        const node = new CacheNode('node-1');
        node.set('key1', 'value1', 100);
        
        await new Promise(r => setTimeout(r, 150));
        
        expect(node.get('key1')).toBeNull();
    });
});
