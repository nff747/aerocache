import { ConsistentHashing } from "./ConsistentHashing";
import { CacheNode } from "./CacheNode";

export class DistributedCache {
    private nodes: Map<string, CacheNode>;
    private ring: ConsistentHashing;

    constructor() {
        this.nodes = new Map();
        this.ring = new ConsistentHashing();
    }

    addNode(node: CacheNode): void {
        this.nodes.set(node.getId(), node);
        this.ring.addNode(node.getId());
    }

    removeNode(nodeId: string): void {
        this.nodes.delete(nodeId);
        this.ring.removeNode(nodeId);
    }
}
