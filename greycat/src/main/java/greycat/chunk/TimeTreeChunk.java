/**
 * Copyright 2017 The GreyCat Authors.  All rights reserved.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package greycat.chunk;

public interface TimeTreeChunk extends Chunk {

    int insert(long key);

    long getKey(int offset);

    long previousOrEqual(long key);

    int previousOrEqualOffset(long key);

    void range(long startKey, long endKey, long maxElements, TreeWalker walker);

    long magic();

    long previous(long key);

    long next(long key);

    int size();

    long capacity();

    void setCapacity(long v);

    long max();

}