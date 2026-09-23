type CacheItem = {
    value: any;
    expireAt: number;
};

export class CacheNode {
    private id: string;
    private data: Map<string, CacheItem>;
    
    constructor(id: string) {
        this.id = id;
        this.data = new Map();
    }

    getId(): string {
        return this.id;
    }

    set(key: string, value: any, ttlMs: number): void {
        this.data.set(key, {
            value,
            expireAt: Date.now() + ttlMs
        });
    }

    get(key: string): any {
        const item = this.data.get(key);
        if (!item) return null;
        if (Date.now() > item.expireAt) {
            this.data.delete(key);
            return null;
        }
        return item.value;
    }
}
