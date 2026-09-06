package io.aerocache.collection;

/**
 * Reusable zero-allocation result container for off-heap hash map lookups.
 * Avoids any heap allocation during GET operations.
 */
public final class EntryResult {
    public boolean found;
    public long valAddress;
    public int valLength;

    public void set(long valAddress, int valLength) {
        this.found = true;
        this.valAddress = valAddress;
        this.valLength = valLength;
    }

    public void clear() {
        this.found = false;
        this.valAddress = 0;
        this.valLength = 0;
    }
}
