import { describe, it, expect } from 'vitest';
import { ConsistentHashing } from '../src/ConsistentHashing';

describe('ConsistentHashing', () => {
    it('should assign a key to a node', () => {
        const ring = new ConsistentHashing();
        ring.addNode('node-A');
        ring.addNode('node-B');
        ring.addNode('node-C');

        const assignedNode = ring.getNode('my-key');
        expect(['node-A', 'node-B', 'node-C']).toContain(assignedNode);
    });

    it('should return null if no nodes are available', () => {
        const ring = new ConsistentHashing();
        expect(ring.getNode('key')).toBeNull();
    });

    it('should remove a node and reassign keys', () => {
        const ring = new ConsistentHashing();
        ring.addNode('node-A');
        const node1 = ring.getNode('key1');
        expect(node1).toBe('node-A');

        ring.removeNode('node-A');
        expect(ring.getNode('key1')).toBeNull();
    });
});
