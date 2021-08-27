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
package edu.psu.cse.siis.coal.values;

/**
 * A "bottom" COAL propagation value.
 */
public final class BottomPropagationValue implements BasePropagationValue {
  private static final edu.psu.cse.siis.coal.values.BottomPropagationValue instance = new edu.psu.cse.siis.coal.values.BottomPropagationValue();

  private BottomPropagationValue() {
  }

  /**
   * Returns the singleton instance for this class.
   *
   * @return The singleton instance for this class.
   */
  public static edu.psu.cse.siis.coal.values.BottomPropagationValue v() {
    return instance;
  }

  @Override
  public String toString() {
    return "bottom";
  }
}
