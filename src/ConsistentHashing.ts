export class ConsistentHashing {
    private ring: Map<number, string>;
    private keys: number[];

    constructor() {
        this.ring = new Map();
        this.keys = [];
    }
}
