export class CacheNode {
    private id: string;
    private data: Map<string, any>;
    
    constructor(id: string) {
        this.id = id;
        this.data = new Map();
    }

    getId(): string {
        return this.id;
    }
}
