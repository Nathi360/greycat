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
package greycat.modeling.language.ast;


import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class Class implements Classifier {
    private final String name;
    private final Map<String, Property> properties;
    private final Set<GlobalIndex> globalIndexes;
    private final Set<LocalIndex> localIndexes;

    private Class parent;

    public Class(String p_name) {
        name = p_name;
        properties = new HashMap<>();
        globalIndexes = new HashSet<>();
        localIndexes = new HashSet<>();
    }

    public Property[] properties() {
        return properties.values().toArray(new Property[properties.size()]);
    }

    public Property property(String name) {
        for (Property property : properties()) {
            if (property.name().equals(name)) {
                return property;
            }
        }
        return null;
    }

    public void addProperty(Property property) {
        properties.put(property.name(), property);
    }

    public Class parent() {
        return parent;
    }

    public void setParent(Class parent) {
        this.parent = parent;
    }

    public void addGlobalIndex(GlobalIndex index) {
        globalIndexes.add(index);
    }

    public GlobalIndex[] globalIndexes() {
        return globalIndexes.toArray(new GlobalIndex[globalIndexes.size()]);
    }

    public void addLocalIndex(LocalIndex index) {
        localIndexes.add(index);
    }

    public LocalIndex[] localIndexes() {
        return localIndexes.toArray(new LocalIndex[localIndexes.size()]);
    }

    @Override
    public String name() {
        return name;
    }
}
