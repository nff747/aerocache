import { ConsistentHashing } from "./ConsistentHashing";
import { CacheNode } from "./CacheNode";

export class DistributedCache {
    private nodes: Map<string, CacheNode>;
    private ring: ConsistentHashing;

    constructor() {
        this.nodes = new Map();
        this.ring = new ConsistentHashing();
    }
}
