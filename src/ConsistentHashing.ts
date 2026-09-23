import * as crypto from 'crypto';

export class ConsistentHashing {
    private ring: Map<number, string>;
    private keys: number[];
    private readonly replicas = 3;

    constructor() {
        this.ring = new Map();
        this.keys = [];
    }

    private hash(key: string): number {
        const hash = crypto.createHash('md5').update(key).digest('hex');
        return parseInt(hash.substring(0, 8), 16);
    }

    addNode(nodeId: string): void {
        for (let i = 0; i < this.replicas; i++) {
            const key = this.hash(`${nodeId}:${i}`);
            this.ring.set(key, nodeId);
            this.keys.push(key);
        }
        this.keys.sort((a, b) => a - b);
    }

    removeNode(nodeId: string): void {
        for (let i = 0; i < this.replicas; i++) {
            const key = this.hash(`${nodeId}:${i}`);
            this.ring.delete(key);
            this.keys = this.keys.filter(k => k !== key);
        }
    }

    getNode(key: string): string | null {
        if (this.keys.length === 0) return null;
        const hash = this.hash(key);
        for (const k of this.keys) {
            if (hash <= k) {
                return this.ring.get(k)!;
            }
        }
        return this.ring.get(this.keys[0])!;
    }
}
