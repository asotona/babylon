/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package java.lang.reflect.code.descriptor.impl;

import java.lang.reflect.code.descriptor.MethodTypeDesc;
import java.lang.reflect.code.descriptor.TypeDesc;
import java.util.List;

import static java.util.stream.Collectors.joining;

public final class MethodTypeDescImpl implements MethodTypeDesc {
    final TypeDesc rtype;
    final List<TypeDesc> ptypes;

    public MethodTypeDescImpl(TypeDesc rtype, List<TypeDesc> ptypes) {
        this.rtype = rtype;
        this.ptypes = List.copyOf(ptypes);
    }

    @Override
    public TypeDesc returnType() {
        return rtype;
    }

    @Override
    public List<TypeDesc> parameters() {
        return ptypes;
    }

    @Override
    public String toString() {
        return ptypes.stream().map(TypeDesc::toString)
                .collect(joining(", ", "(", ")")) + rtype.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        MethodTypeDescImpl that = (MethodTypeDescImpl) o;

        if (!rtype.equals(that.rtype)) return false;
        return ptypes.equals(that.ptypes);
    }

    @Override
    public int hashCode() {
        int result = rtype.hashCode();
        result = 31 * result + ptypes.hashCode();
        return result;
    }

    // Conversions

    @Override
    public MethodTypeDesc erase() {
        return new MethodTypeDescImpl(rtype.rawType(), ptypes.stream().map(TypeDesc::rawType).toList());
    }

    @Override
    public String toNominalDescriptorString() {
        return ptypes.stream()
                .map(TypeDesc::toNominalDescriptorString)
                .collect(joining("", "(", ")")) +
                rtype.toNominalDescriptorString();
    }
}
