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
package edu.psu.cse.siis.coal.field.transformers;

import edu.psu.cse.siis.coal.field.values.FieldValue;

/**
 * The identity field transformer, which does not modify anything.
 */
public class IdentityFieldTransformer extends FieldTransformer {
  private static final edu.psu.cse.siis.coal.field.transformers.IdentityFieldTransformer instance = new edu.psu.cse.siis.coal.field.transformers.IdentityFieldTransformer();

  private IdentityFieldTransformer() {
  }

  @Override
  public FieldValue apply(FieldValue fieldValue) {
    return fieldValue;
  }

  @Override
  public FieldTransformer compose(FieldTransformer secondFieldOperation) {
    return secondFieldOperation;
  }

  @Override
  public String toString() {
    return "identity";
  }

  public static edu.psu.cse.siis.coal.field.transformers.IdentityFieldTransformer v() {
    return instance;
  }
}
