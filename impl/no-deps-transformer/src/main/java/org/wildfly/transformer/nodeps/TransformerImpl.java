/*
 * Copyright 2020 Red Hat, Inc, and individual contributors.
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
package org.wildfly.transformer.nodeps;

import static java.lang.System.arraycopy;
import static java.lang.Thread.currentThread;
import static org.wildfly.transformer.nodeps.ClassFileUtils.*;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.wildfly.transformer.Transformer;

/**
 * Class file transformer.
 * Instances of this class are thread safe.
 *
 * @author <a href="mailto:ropalka@redhat.com">Richard Opálka</a>
 */
final class TransformerImpl implements Transformer {

    private static final String CLASS_SUFFIX = ".class";
    private static final String XML_SUFFIX = ".xml";
    private static final String META_INF_SERVICES_PREFIX = "META-INF/services/";

    /**
     * Debugging support.
     */
    private static final boolean DEBUG = Boolean.getBoolean(TransformerImpl.class.getName() + ".debug");

    /**
     * Packages mapping with '/' char.
     */
    final Map<String, String> mappingWithSeps;

    /**
     * Packages mapping with '.' char.
     */
    final Map<String, String> mappingWithDots;

    /**
     * Represents strings we are searching for in <code>CONSTANT_Utf8_info</code> structures (encoded in modified UTF-8).
     * Mapping on index <code>zero</code> is undefined. Mappings are defined from index <code>one</code>.
     */
    private final byte[][] mappingFrom;

    /**
     * Represents strings we will replace matches with inside <code>CONSTANT_Utf8_info</code> structures (encoded in modified UTF-8).
     * Mapping on index <code>zero</code> is undefined. Mappings are defined from index <code>one</code>.
     */
    private final byte[][] mappingTo;

    /**
     * Used for detecting maximum size of internal patch info arrays and for decreasing patch search space.
     */
    private final int minimum;

    /**
     * Constructor.
     *
     * @param mappingWithSeps packages mapping in path separator form
     * @param mappingWithDots packages mapping in dot form
     */
    TransformerImpl(final Map<String, String> mappingWithSeps, final Map<String, String> mappingWithDots) {
        this.mappingWithSeps = mappingWithSeps;
        this.mappingWithDots =  mappingWithDots;
        final int arraySize = mappingWithSeps.size() + mappingWithDots.size() + 1;
        this.mappingFrom = new byte[arraySize][];
        this.mappingTo = new byte[arraySize][];
        int i = 1;
        int minimum = Integer.MAX_VALUE;
        for (Map.Entry<String, String> mappingEntry : mappingWithSeps.entrySet()) {
            mappingFrom[i] = stringToUtf8(mappingEntry.getKey());
            mappingTo[i] = stringToUtf8(mappingEntry.getValue());
            if (minimum > mappingFrom[i].length) {
                minimum = mappingFrom[i].length;
            }
            i++;
        }
        for (Map.Entry<String, String> mappingEntry : mappingWithDots.entrySet()) {
            mappingFrom[i] = stringToUtf8(mappingEntry.getKey());
            mappingTo[i] = stringToUtf8(mappingEntry.getValue());
            if (minimum > mappingFrom[i].length) {
                minimum = mappingFrom[i].length;
            }
            i++;
        }
        this.minimum = minimum;
    }

    @Override
    public Resource transform(final Resource r) {
        String oldResourceName = r.getName();
        String newResourceName = replacePackageName(oldResourceName, false);
        if (oldResourceName.endsWith(CLASS_SUFFIX)) {
            final byte[] newClazz = transform(r.getData());
            if (newClazz != null) return new Resource(newResourceName, newClazz);
        } else if (oldResourceName.endsWith(XML_SUFFIX)) {
            return new Resource(newResourceName, xmlFile(r.getData()));
        } else if (oldResourceName.startsWith(META_INF_SERVICES_PREFIX)) {
            newResourceName = replacePackageName(oldResourceName, true);
            if (!newResourceName.equals(oldResourceName)) {
                return new Resource(newResourceName, r.getData());
            }
        } else if (!newResourceName.equals(oldResourceName)) {
            return new Resource(newResourceName, r.getData());
        }
        return null; // returning null means nothing was transformed (indicates copy original content)
    }

    private String replacePackageName(final String resourceName, final boolean dotFormat) {
        int startIndex;
        for (final Map.Entry<String, String> mapping : (dotFormat ? mappingWithDots : mappingWithSeps).entrySet()) {
            startIndex = resourceName.indexOf(mapping.getKey());
            if (startIndex != -1) {
                return resourceName.substring(0, startIndex) + mapping.getValue() + resourceName.substring(startIndex + mapping.getKey().length());
            }
        }
        return resourceName;
    }

    private static byte[] xmlFile(final byte[] data) {
        try {
            // TODO: use mapping provided in constructor!!!
            return new String(data, "UTF-8").replace("javax.", "jakarta.").getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            return null; // should never happen
        }
    }

    private byte[] transform(final byte[] clazz) {
        final int[] constantPool = getConstantPool(clazz);
        int diffInBytes = 0, position, utf8Length;
        byte tag;
        List<int[]> patches = null;
        int[] patch;

        for (int i = 1; i < constantPool.length; i++) {
            position = constantPool[i];
            if (position == 0) continue;
            tag = clazz[position++];
            if (tag == UTF8) {
                utf8Length = readUnsignedShort(clazz, position);
                position += 2;
                patch = getPatch(clazz, position, position + utf8Length, i);
                if (patch != null) {
                    if (patches == null) {
                        patches = new ArrayList<>(countUtf8Items(clazz, constantPool));
                    }
                    diffInBytes += patch[1];
                    patches.add(patch);
                }
            }
        }
        if (diffInBytes > 0 && Integer.MAX_VALUE - diffInBytes < clazz.length) {
            throw new UnsupportedOperationException("Couldn't patch class file. The transformed class file would exceed max allowed size " + Integer.MAX_VALUE + " bytes");
        }
        String thisClass = null;
        if (DEBUG && patches != null) {
            final int thisClassPoolIndex = readUnsignedShort(clazz, constantPool[0] + 2);
            final int thisClassUtf8Position = constantPool[readUnsignedShort(clazz, constantPool[thisClassPoolIndex] + 1)];
            final int thisClassUtf8Length = readUnsignedShort(clazz, thisClassUtf8Position + 1);
            if (DEBUG) {
                synchronized (System.out) {
                    thisClass = utf8ToString(clazz, thisClassUtf8Position + 3, thisClassUtf8Position + thisClassUtf8Length + 3);
                    System.out.println("[" + currentThread() + "] Patching class " + thisClass + " - START");
                }
            }
        }
        try {
            return patches == null ? null : applyPatches(clazz, clazz.length + diffInBytes, constantPool, patches);
        } finally {
            if (DEBUG && patches != null) {
                synchronized (System.out) {
                    System.out.println("[" + currentThread() + "] Patching class " + thisClass + " - END");
                }
            }
        }
    }

    /**
     * Returns modified class byte code with patches applied.
     *
     * @param oldClass original class byte code
     * @param newClassSize count of bytes of new class byte code
     * @param oldClassConstantPool pointers to old class constant pool items
     * @param patches patches to apply
     * @return modified class byte code with patches applied
     */
    private byte[] applyPatches(final byte[] oldClass, final int newClassSize, final int[] oldClassConstantPool, final List<int[]> patches) {
        final byte[] newClass = new byte[newClassSize];
        int oldClassOffset = 0, newClassOffset = 0;
        int length, mappingIndex, oldUtf8ItemBytesSectionOffset, oldUtf8ItemLength, patchOffset;
        int debugOldUtf8ItemOffset = -1, debugNewUtf8ItemOffset = -1;
        int debugOldUtf8ItemLength = -1, debugNewUtf8ItemLength = -1;

        // First copy magic, version and constant pool size
        arraycopy(oldClass, oldClassOffset, newClass, newClassOffset, POOL_CONTENT_INDEX);
        oldClassOffset = newClassOffset = POOL_CONTENT_INDEX;

        for (int[] patch : patches) {
            if (patch == null) break;
            oldUtf8ItemBytesSectionOffset = oldClassConstantPool[patch[0]] + 3;
            // copy till start of next utf8 item bytes section
            length = oldUtf8ItemBytesSectionOffset - oldClassOffset;
            arraycopy(oldClass, oldClassOffset, newClass, newClassOffset, length);
            oldClassOffset += length;
            newClassOffset += length;
            if (DEBUG) {
                debugOldUtf8ItemOffset = oldClassOffset;
                debugNewUtf8ItemOffset = newClassOffset;
            }
            // patch utf8 item length
            oldUtf8ItemLength = readUnsignedShort(oldClass, oldClassOffset - 2);
            writeUnsignedShort(newClass, newClassOffset - 2, oldUtf8ItemLength + patch[1]);
            // apply utf8 info bytes section patches
            for (int i = 2; i < patch.length;) {
                mappingIndex = patch[i++];
                if (mappingIndex == 0) break;
                patchOffset = patch[i++];
                // copy till begin of patch
                length = patchOffset - (oldClassOffset - oldUtf8ItemBytesSectionOffset);
                arraycopy(oldClass, oldClassOffset, newClass, newClassOffset, length);
                oldClassOffset += length;
                newClassOffset += length;
                // apply patch
                arraycopy(mappingTo[mappingIndex], 0, newClass, newClassOffset, mappingTo[mappingIndex].length);
                oldClassOffset += mappingFrom[mappingIndex].length;
                newClassOffset += mappingTo[mappingIndex].length;
            }
            // copy remaining class byte code till utf8 item end
            length = oldUtf8ItemBytesSectionOffset + oldUtf8ItemLength - oldClassOffset;
            arraycopy(oldClass, oldClassOffset, newClass, newClassOffset, length);
            oldClassOffset += length;
            newClassOffset += length;
            if (DEBUG) {
                synchronized (System.out) {
                    System.out.println("[" + currentThread() + "] Patching UTF-8 constant pool item on position: " + patch[0]);
                    debugOldUtf8ItemLength = readUnsignedShort(oldClass, debugOldUtf8ItemOffset - 2);
                    System.out.println("[" + currentThread() + "] old value: " + utf8ToString(oldClass, debugOldUtf8ItemOffset, debugOldUtf8ItemOffset + debugOldUtf8ItemLength));
                    debugNewUtf8ItemLength = readUnsignedShort(newClass, debugNewUtf8ItemOffset - 2);
                    System.out.println("[" + currentThread() + "] new value: " + utf8ToString(newClass, debugNewUtf8ItemOffset, debugNewUtf8ItemOffset + debugNewUtf8ItemLength));
                }
            }
        }

        // copy remaining class byte code
        arraycopy(oldClass, oldClassOffset, newClass, newClassOffset, oldClass.length - oldClassOffset);

        return newClass;
    }

    /**
     * Returns <code>patch info</code> if patches were detected or <code>null</code> if there is no patch applicable.
     * Every <code>patch info</code> has the following format:
     * <p>
     *     <pre>
     *        +-------------+ PATCH INFO STRUCTURE HEADER
     *        | integer 0   | holds <code>Constant_Utf8_info</code> index inside class's <code>constant pool</code> table
     *        | integer 1   | holds <code>CONSTANT_Utf8_info</code> structure difference in bytes after applied patches
     *        +-------------+ PATCH INFO STRUCTURE DATA
     *        | integer 2   | holds non-zero mapping index in mapping tables of 1-st applied patch
     *        | integer 3   | holds index of 1-st patch start inside bytes section of original <code>CONSTANT_Utf8_info</code> structure
     *        +-------------+
     *        | integer 4   | holds non-zero mapping index in mapping tables of 2-nd applied patch
     *        | integer 5   | holds index of 2-nd patch start inside bytes section of original <code>CONSTANT_Utf8_info</code> structure
     *        +-------------+
     *        |    ...      | etc
     *        |             |
     *        +-------------+
     *        | integer N-1 | mapping index equal to zero indicates premature <code>patch info</code> structure end
     *        | integer N   |
     *        +-------------+
     *     </pre>
     * </p>
     *
     * @param clazz class byte code
     * @param offset beginning index of <code>CONSTANT_Utf8_info</code> structure being investigated
     * @param limit first index not belonging to investigated <code>CONSTANT_Utf8_info</code> structure
     * @return
     */
    private int[] getPatch(final byte[] clazz, final int offset, final int limit, final int poolIndex) {
        int[] retVal = null;
        int mappingIndex;
        int patchIndex = 2;

        for (int i = offset; i <= limit - minimum; i++) {
            for (int j = 1; j < mappingFrom.length; j++) {
                if (limit - i < mappingFrom[j].length) continue;
                mappingIndex = j;
                for (int k = 0; k < mappingFrom[j].length; k++) {
                    if (clazz[i + k] != mappingFrom[j][k]) {
                        mappingIndex = 0;
                        break;
                    }
                }
                if (mappingIndex != 0) {
                    if (retVal == null) {
                        retVal = new int[(((limit - i) / minimum) + 1) * 2];
                        retVal[0] = poolIndex;
                    }
                    retVal[patchIndex++] = mappingIndex;
                    retVal[patchIndex++] = i - offset;
                    retVal[1] += mappingTo[mappingIndex].length - mappingFrom[mappingIndex].length;
                    i += mappingFrom[j].length;
                }
            }
        }

        return retVal;
    }

}
