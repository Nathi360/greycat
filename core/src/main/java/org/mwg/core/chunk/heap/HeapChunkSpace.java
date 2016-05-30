
package org.mwg.core.chunk.heap;

import org.mwg.Callback;
import org.mwg.Graph;
import org.mwg.core.CoreConstants;
import org.mwg.core.utility.BufferBuilder;
import org.mwg.plugin.Chunk;
import org.mwg.plugin.ChunkIterator;
import org.mwg.plugin.ChunkSpace;
import org.mwg.plugin.ChunkType;
import org.mwg.struct.*;
import org.mwg.core.chunk.*;
import org.mwg.core.utility.PrimitiveHelper;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;

public class HeapChunkSpace implements ChunkSpace, ChunkListener {

    /**
     * Global variables
     */
    private final int _maxEntries;
    private final int _saveBatchSize;
    // TODO here I think the AtomicInteger is needed -> ok
    private final AtomicInteger _elementCount;
    private final Stack _lru;
    private Graph _graph;

    /**
     * HashMap variables
     */
    private final int[] _elementNext;
    private final int[] _elementHash;
    private final Chunk[] _values;
    private final AtomicIntegerArray _elementHashLock;
    private final AtomicReference<InternalDirtyStateList> _dirtyState;

    @Override
    public void setGraph(Graph p_graph) {
        this._graph = p_graph;
    }

    @Override
    public Graph graph() {
        return this._graph;
    }

    final class InternalDirtyStateList implements ChunkIterator {

        private final AtomicInteger _nextCounter;
        private final int[] _dirtyElements;
        private final int _max;
        private final AtomicInteger _iterationCounter;
        private final HeapChunkSpace _parent;

        public InternalDirtyStateList(int maxSize, HeapChunkSpace p_parent) {

            this._dirtyElements = new int[maxSize];

            this._nextCounter = new AtomicInteger(0);
            this._iterationCounter = new AtomicInteger(0);

            this._max = maxSize;
            this._parent = p_parent;
        }

        @Override
        public boolean hasNext() {
            return this._iterationCounter.get() < this._nextCounter.get();
        }

        @Override
        public Chunk next() {
            int previous;
            int next;
            do {
                previous = this._iterationCounter.get();
                if (this._nextCounter.get() == previous) {
                    return null;
                }
                next = previous + 1;
            } while (!this._iterationCounter.compareAndSet(previous, next));
            return this._parent.getValues()[this._dirtyElements[previous]];
        }

        public boolean declareDirty(int dirtyIndex) {
            int previousDirty;
            int nextDirty;
            do {
                previousDirty = this._nextCounter.get();
                if (previousDirty == this._max) {
                    return false;
                }
                nextDirty = previousDirty + 1;
            } while (!this._nextCounter.compareAndSet(previousDirty, nextDirty));
            //ok we have the token previous
            this._dirtyElements[previousDirty] = dirtyIndex;
            return true;
        }

        @Override
        public long size() {
            return this._nextCounter.get();
        }

        @Override
        public void free() {
            //noop
        }
    }

    public Chunk[] getValues() {
        return _values;
    }

    public HeapChunkSpace(int initialCapacity, int saveBatchSize) {

        if (saveBatchSize > initialCapacity) {
            throw new RuntimeException("Save Batch Size can't be bigger than cache size");
        }

        this._maxEntries = initialCapacity;
        this._saveBatchSize = saveBatchSize;
        this._lru = new FixedStack(initialCapacity);
        this._dirtyState = new AtomicReference<InternalDirtyStateList>();
        this._dirtyState.set(new InternalDirtyStateList(saveBatchSize, this));

        //init std variables
        this._elementNext = new int[initialCapacity];
        this._elementHashLock = new AtomicIntegerArray(new int[initialCapacity]);
        this._elementHash = new int[initialCapacity];
        this._values = new Chunk[initialCapacity];
        this._elementCount = new AtomicInteger(0);

        //init internal structures
        for (int i = 0; i < initialCapacity; i++) {
            this._elementNext[i] = -1;
            this._elementHash[i] = -1;
            this._elementHashLock.set(i, -1);
        }
    }

    @Override
    public final Chunk getAndMark(byte type, long world, long time, long id) {
        int index = (int) PrimitiveHelper.tripleHash(type, world, time, id, this._maxEntries);
        int m = this._elementHash[index];
        while (m != -1) {
            HeapChunk foundChunk = (HeapChunk) this._values[m];
            if (foundChunk != null && type == foundChunk.chunkType() && world == foundChunk.world() && time == foundChunk.time() && id == foundChunk.id()) {
                //GET VALUE
                if (foundChunk.mark() == 1) {
                    //was at zero before, risky operation, check selectWith LRU
                    if (this._lru.dequeue(m)) {
                        return foundChunk;
                    } else {
                        if (foundChunk.marks() > 1) {
                            //ok fine we are several on the same object...
                        } else {
                            //better return null the object will be recycled by somebody else...
                            return null;
                        }
                    }
                } else {
                    return foundChunk;
                }
            } else {
                m = this._elementNext[m];
            }
        }
        return null;
    }

    @Override
    public void getOrLoadAndMark(byte type, long world, long time, long id, Callback<Chunk> callback) {
        Chunk fromMemory = getAndMark(type, world, time, id);
        if (fromMemory != null) {
            callback.on(fromMemory);
        } else {
            Buffer keys = graph().newBuffer();
            BufferBuilder.keyToBuffer(keys, type, world, time, id);
            graph().storage().get(keys, new Callback<Buffer>() {
                @Override
                public void on(Buffer result) {
                    if (result != null) {
                        Chunk loadedChunk_0 = create(type, world, time, id, result, null);
                        result.free();
                        if (loadedChunk_0 == null) {
                            callback.on(null);
                        } else {
                            Chunk loadedChunk = putAndMark(loadedChunk_0);
                            if (loadedChunk != loadedChunk_0) {
                                freeChunk(loadedChunk_0);
                            }
                            callback.on(loadedChunk);
                        }
                    } else {
                        keys.free();
                        callback.on(null);
                    }
                }
            });
        }
    }

    @Override
    public void unmark(byte type, long world, long time, long id) {
        int index = (int) PrimitiveHelper.tripleHash(type, world, time, id, this._maxEntries);
        int m = this._elementHash[index];
        while (m != -1) {
            HeapChunk foundChunk = (HeapChunk) this._values[m];
            if (foundChunk != null && type == foundChunk.chunkType() && world == foundChunk.world() && time == foundChunk.time() && id == foundChunk.id()) {
                if (foundChunk.unmark() == 0) {
                    //declare available for recycling
                    this._lru.enqueue(m);
                }
                return;
            } else {
                m = this._elementNext[m];
            }
        }
    }

    @Override
    public void unmarkChunk(Chunk chunk) {
        HeapChunk heapChunk = (HeapChunk) chunk;
        if (heapChunk.unmark() == 0) {
            long nodeWorld = chunk.world();
            long nodeTime = chunk.time();
            long nodeId = chunk.id();
            byte nodeType = chunk.chunkType();
            int index = (int) PrimitiveHelper.tripleHash(chunk.chunkType(), nodeWorld, nodeTime, nodeId, this._maxEntries);
            int m = this._elementHash[index];
            while (m != -1) {
                Chunk foundChunk = this._values[m];
                if (foundChunk != null && nodeType == foundChunk.chunkType() && nodeWorld == foundChunk.world() && nodeTime == foundChunk.time() && nodeId == foundChunk.id()) {
                    //chunk is available for recycling
                    this._lru.enqueue(m);
                    return;
                } else {
                    m = this._elementNext[m];
                }
            }
        }
    }

    @Override
    public void freeChunk(Chunk chunk) {
        //NOOP
    }

    @Override
    public Chunk create(byte p_type, long p_world, long p_time, long p_id, Buffer p_initialPayload, Chunk origin) {
        switch (p_type) {
            case ChunkType.STATE_CHUNK:
                return new HeapStateChunk(p_world, p_time, p_id, this, p_initialPayload, origin);
            case ChunkType.WORLD_ORDER_CHUNK:
                return new HeapWorldOrderChunk(p_world, p_time, p_id, this, p_initialPayload);
            case ChunkType.TIME_TREE_CHUNK:
                return new HeapTimeTreeChunk(p_world, p_time, p_id, this, p_initialPayload);
            case ChunkType.GEN_CHUNK:
                return new HeapGenChunk(p_world, p_time, p_id, this, p_initialPayload);
        }
        return null;
    }

    @Override
    public Chunk putAndMark(Chunk p_elem) {
        //first mark the object
        HeapChunk heapChunk = (HeapChunk) p_elem;
        if (heapChunk.mark() != 1) {
            throw new RuntimeException("Warning, trying to put an unsafe object " + p_elem);
        }
        int entry = -1;
        int hashIndex = (int) PrimitiveHelper.tripleHash(p_elem.chunkType(), p_elem.world(), p_elem.time(), p_elem.id(), this._maxEntries);
        int m = this._elementHash[hashIndex];
        while (m >= 0) {
            Chunk currentM = this._values[m];
            if (currentM != null && p_elem.chunkType() == currentM.chunkType() && p_elem.world() == currentM.world() && p_elem.time() == currentM.time() && p_elem.id() == currentM.id()) {
                entry = m;
                break;
            }
            m = this._elementNext[m];
        }
        if (entry == -1) {
            //we look for nextIndex
            int currentVictimIndex = (int) this._lru.dequeueTail();
            if (currentVictimIndex == -1) {
                //TODO cache is full :(
                System.gc();
                try {
                    Thread.sleep(100);
                } catch (Exception e) {
                    e.printStackTrace();
                }

                currentVictimIndex = (int) this._lru.dequeueTail();
                if (currentVictimIndex == -1) {
                    throw new RuntimeException("mwDB crashed, cache is full, please avoid to much retention of nodes or augment cache capacity!");
                }
            }
            if (this._values[currentVictimIndex] != null) {
                Chunk victim = this._values[currentVictimIndex];
                long victimWorld = victim.world();
                long victimTime = victim.time();
                long victimObj = victim.id();
                byte victimType = victim.chunkType();
                int indexVictim = (int) PrimitiveHelper.tripleHash(victimType, victimWorld, victimTime, victimObj, this._maxEntries);

                //negociate a lock on the indexVictim hash
                while (!this._elementHashLock.compareAndSet(indexVictim, -1, 1)) ;
                //we obtains the token, now remove the element
                m = _elementHash[indexVictim];
                int last = -1;
                while (m >= 0) {
                    Chunk currentM = this._values[m];
                    if (currentM != null && victimType == currentM.chunkType() && victimWorld == currentM.world() && victimTime == currentM.time() && victimObj == currentM.id()) {
                        break;
                    }
                    last = m;
                    m = _elementNext[m];
                }
                //POP THE VALUE FROM THE NEXT LIST
                if (last == -1) {
                    int previousNext = _elementNext[m];
                    _elementHash[indexVictim] = previousNext;
                } else {
                    if (m == -1) {
                        _elementNext[last] = -1;
                    } else {
                        _elementNext[last] = _elementNext[m];
                    }
                }
                _elementNext[m] = -1;//flag to dropped value
                //UNREF victim value object
                _values[currentVictimIndex] = null;

                //free the lock
                this._elementHashLock.set(indexVictim, -1);
                this._elementCount.decrementAndGet();
            }
            _values[currentVictimIndex] = p_elem;
            //negociate the lock to write on hashIndex
            while (!this._elementHashLock.compareAndSet(hashIndex, -1, 1)) ;
            _elementNext[currentVictimIndex] = _elementHash[hashIndex];
            _elementHash[hashIndex] = currentVictimIndex;
            //free the lock
            this._elementHashLock.set(hashIndex, -1);
            this._elementCount.incrementAndGet();
            return p_elem;
        } else {
            return _values[entry];
        }
    }

    @Override
    public ChunkIterator detachDirties() {
        return _dirtyState.getAndSet(new InternalDirtyStateList(this._saveBatchSize, this));
    }

    @Override
    public void declareDirty(Chunk dirtyChunk) {
        long world = dirtyChunk.world();
        long time = dirtyChunk.time();
        long id = dirtyChunk.id();
        byte type = dirtyChunk.chunkType();
        int hashIndex = (int) PrimitiveHelper.tripleHash(type, world, time, id, this._maxEntries);
        int m = this._elementHash[hashIndex];
        while (m >= 0) {
            HeapChunk currentM = (HeapChunk) this._values[m];
            if (currentM != null && type == currentM.chunkType() && world == currentM.world() && time == currentM.time() && id == currentM.id()) {
                if (currentM.setFlags(CoreConstants.DIRTY_BIT, 0)) {
                    //add an additional mark
                    currentM.mark();
                    //now enqueue in the dirtyList to be saved later
                    boolean success = false;
                    while (!success) {
                        InternalDirtyStateList previousState = this._dirtyState.get();
                        success = previousState.declareDirty(m);
                        if (!success) {
                            this._graph.save(null);
                        }
                    }
                }

                return;
            }
            m = this._elementNext[m];
        }
        throw new RuntimeException("Try to declare a non existing object!");
    }

    @Override
    public void declareClean(Chunk cleanChunk) {
        HeapChunk heapChunk = (HeapChunk) cleanChunk;
        long world = cleanChunk.world();
        long time = cleanChunk.time();
        long id = cleanChunk.id();
        byte type = cleanChunk.chunkType();
        int hashIndex = (int) PrimitiveHelper.tripleHash(type, world, time, id, this._maxEntries);
        int m = this._elementHash[hashIndex];
        while (m >= 0) {
            HeapChunk currentM = (HeapChunk) this._values[m];
            if (currentM != null && type == currentM.chunkType() && world == currentM.world() && time == currentM.time() && id == currentM.id()) {
                currentM.setFlags(0, CoreConstants.DIRTY_BIT);
                //free the save mark
                if (heapChunk.unmark() == 0) {
                    this._lru.enqueue(m);
                }
                return;
            }
            m = this._elementNext[m];
        }
        throw new RuntimeException("Try to declare a non existing object!");

    }

    @Override
    public final void clear() {
        //TODO
    }

    @Override
    public void free() {
        //TODO
    }

    @Override
    public final long size() {
        return this._elementCount.get();
    }

    @Override
    public long available() {
        return _lru.size();
    }

}



