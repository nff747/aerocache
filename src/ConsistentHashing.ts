import * as crypto from 'crypto';

export class ConsistentHashing {
    private ring: Map<number, string>;
    private keys: number[];

    constructor() {
        this.ring = new Map();
        this.keys = [];
    }

    private hash(key: string): number {
        const hash = crypto.createHash('md5').update(key).digest('hex');
        return parseInt(hash.substring(0, 8), 16);
    }
}
