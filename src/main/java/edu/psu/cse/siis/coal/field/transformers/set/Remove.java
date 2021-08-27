/*
 * Copyright (C) 2015 The Pennsylvania State University and the University of Wisconsin
 * Systems and Internet Infrastructure Security Laboratory
 *
 * Author: Damien Octeau
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.psu.cse.siis.coal.field.transformers.set;

import edu.psu.cse.siis.coal.field.transformers.FieldTransformer;

import java.util.HashSet;

/**
 * A field transformer for remove operations;
 */
public class Remove extends SetFieldTransformer {
  public Remove(Object value) {
    this.remove = new HashSet<>(2);
    this.remove.add(value);
  }

  public Remove(edu.psu.cse.siis.coal.field.transformers.set.Remove remove1, edu.psu.cse.siis.coal.field.transformers.set.Remove remove2) {
    this.remove = new HashSet<>(remove1.remove);
    this.remove.addAll(remove2.remove);
  }

  @Override
  public FieldTransformer compose(FieldTransformer secondFieldOperation) {
    if (secondFieldOperation instanceof edu.psu.cse.siis.coal.field.transformers.set.Remove) {
      return new edu.psu.cse.siis.coal.field.transformers.set.Remove(this, (edu.psu.cse.siis.coal.field.transformers.set.Remove) secondFieldOperation);
    } else {
      return super.compose(secondFieldOperation);
    }
  }
}
