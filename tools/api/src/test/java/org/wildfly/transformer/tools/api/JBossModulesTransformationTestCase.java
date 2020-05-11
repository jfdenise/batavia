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
package org.wildfly.transformer.tools.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 * @author jdenise
 */
public class JBossModulesTransformationTestCase {

    @Test
    public void testFailures() throws Exception {
        {
            boolean failed = false;
            try {
                Common.transformModules(Paths.get("foo"), null, false, null);
                failed = true;
            } catch (Exception ex) {
                // OK should have failed
            }
            if (failed) {
                throw new Exception("Test should have failed");
            }
        }

        {
            Path f = Files.createTempFile("foo", null);
            f.toFile().deleteOnExit();
            boolean failed = false;
            try {
                Common.transformModules(f, null, false, null);
                failed = true;
            } catch (Exception ex) {
                // OK should have failed
            }
            if (failed) {
                throw new Exception("Test should have failed");
            }
        }

        {
            Path modules = Files.createTempDirectory("transform-modules-tests");
            try {
                boolean failed = false;
                try {
                    Common.transformModules(modules, Paths.get("foo").toString(), false, null);
                    failed = true;
                } catch (Exception ex) {
                    // OK should have failed
                }
                if (failed) {
                    throw new Exception("Test should have failed");
                }
            } finally {
                JBossModulesTransformer.recursiveDelete(modules);
            }
        }
    }

    @Test
    public void testTransformModules() throws Exception {
        Path modules = Files.createTempDirectory("transform-modules-tests");
        try {
            Path dir = Files.createDirectories(modules.resolve("javax/foo/bar/main"));
            Path p = dir.resolve("foo.txt");
            Files.write(p, "hello".getBytes());

            Path jsonDir = Files.createDirectories(modules.resolve("javax/json/api/main"));

            String jsonModule = "<module xmlns=\"urn:jboss:module:1.9\" name=\"javax.json.api\">\n"
                    + "</module>";
            Path json = jsonDir.resolve("module.xml");
            Files.write(json, jsonModule.getBytes());
            Path bindDir = Files.createDirectories(modules.resolve("javax/json/bind/api/main"));
            String bindModule = "<module xmlns=\"urn:jboss:module:1.9\" name=\"javax.json.bind.api\">\n"
                    + "    <dependencies>\n"
                    + "        <module name=\"javax.json.api\"/>\n"
                    + "    </dependencies>\n"
                    + "</module>";
            Path bind = bindDir.resolve("module.xml");
            Files.write(bind, bindModule.getBytes());

            Path otherDir = Files.createDirectories(modules.resolve("foo/main"));
            String otherModule = "<module xmlns=\"urn:jboss:module:1.9\" name=\"foo\">\n"
                    + "    <dependencies>\n"
                    + "        <module name=\"javax.json.api\"/>\n"
                    + "        <module name=\"javax.json.bind.api\"/>\n"
                    + "    </dependencies>\n"
                    + "</module>";
            Path other = otherDir.resolve("module.xml");
            Files.write(other, otherModule.getBytes());

            Map<String, TransformedModule> transformedModules = Common.transformModules(modules, null, false, null);
            Assert.assertTrue(transformedModules.size() == 3);
            Assert.assertTrue(Files.exists(p));
            Assert.assertEquals("hello", new String(Files.readAllBytes(p)));

            Assert.assertFalse(Files.exists(json));
            Path jakartaJson = modules.resolve("jakarta/json/api/main/module.xml");
            Assert.assertTrue(Files.exists(jakartaJson));
            Assert.assertFalse(new String(Files.readAllBytes(jakartaJson)).contains("javax"));
            Assert.assertTrue(new String(Files.readAllBytes(jakartaJson)).contains("jakarta.json.api"));

            Assert.assertFalse(Files.exists(bind));
            Path jakartaBind = modules.resolve("jakarta/json/bind/api/main/module.xml");
            Assert.assertTrue(Files.exists(jakartaBind));
            Assert.assertFalse(new String(Files.readAllBytes(jakartaBind)).contains("javax"));
            Assert.assertTrue(new String(Files.readAllBytes(jakartaBind)).contains("jakarta.json.api"));
            Assert.assertTrue(new String(Files.readAllBytes(jakartaBind)).contains("jakarta.json.bind.api"));

            Assert.assertTrue(Files.exists(other));
            Assert.assertFalse(new String(Files.readAllBytes(other)).contains("javax"));
            Assert.assertTrue(new String(Files.readAllBytes(other)).contains("jakarta.json.api"));
            Assert.assertTrue(new String(Files.readAllBytes(other)).contains("jakarta.json.bind.api"));

            TransformedModule jsonTransModule = transformedModules.get("javax.json.api");
            Assert.assertNotNull(jsonTransModule);
            Assert.assertTrue(jsonTransModule.getName().equals("jakarta.json.api"));
            Assert.assertTrue(jsonTransModule.getTransformedDependencies().isEmpty());

            TransformedModule bindTransModule = transformedModules.get("javax.json.bind.api");
            Assert.assertNotNull(bindTransModule);
            Assert.assertTrue(bindTransModule.getName().equals("jakarta.json.bind.api"));
            Assert.assertTrue(bindTransModule.getTransformedDependencies().get("javax.json.api").equals("jakarta.json.api"));

            TransformedModule otherTransModule = transformedModules.get("foo");
            Assert.assertNotNull(otherTransModule);
            Assert.assertTrue(otherTransModule.getName().equals("foo"));
            Assert.assertTrue(otherTransModule.getTransformedDependencies().get("javax.json.api").equals("jakarta.json.api"));
            Assert.assertTrue(otherTransModule.getTransformedDependencies().get("javax.json.bind.api").equals("jakarta.json.bind.api"));

        } finally {
            JBossModulesTransformer.recursiveDelete(modules);
            Assert.assertFalse(Files.exists(modules));
        }
    }

    @Test
    public void testCustomMapping() throws Exception {
        Path modules = Files.createTempDirectory("transform-modules-tests");
        Path mapping = Files.createTempFile("transform-modules-mapping", null);
        String mappingContent = "javax/foo/api=jakarta/foo/api\njavax/foo/bind/api=jakarta/foo/bind/api";
        Files.write(mapping, mappingContent.getBytes());
        mapping.toFile().deleteOnExit();
        try {
            Path dir = Files.createDirectories(modules.resolve("javax/json/api/main"));
            Path p = dir.resolve("foo.txt");
            Files.write(p, "hello".getBytes());

            Path jsonDir = Files.createDirectories(modules.resolve("javax/foo/api/main"));

            String jsonModule = "<module xmlns=\"urn:jboss:module:1.9\" name=\"javax.foo.api\">\n"
                    + "</module>";
            Path json = jsonDir.resolve("module.xml");
            Files.write(json, jsonModule.getBytes());
            Path bindDir = Files.createDirectories(modules.resolve("javax/foo/bind/api/main"));
            String bindModule = "<module xmlns=\"urn:jboss:module:1.9\" name=\"javax.foo.bind.api\">\n"
                    + "    <dependencies>\n"
                    + "        <module name=\"javax.foo.api\"/>\n"
                    + "    </dependencies>\n"
                    + "</module>";
            Path bind = bindDir.resolve("module.xml");
            Files.write(bind, bindModule.getBytes());
            Map<String, TransformedModule> transformedModules = Common.transformModules(modules, mapping.toString(), true, null);
            Assert.assertTrue(transformedModules.size() == 2);
            Assert.assertTrue(Files.exists(p));
            Assert.assertEquals("hello", new String(Files.readAllBytes(p)));

            Assert.assertFalse(Files.exists(json));
            Path jakartaJson = modules.resolve("jakarta/foo/api/main/module.xml");
            Assert.assertTrue(Files.exists(jakartaJson));
            Assert.assertFalse(new String(Files.readAllBytes(jakartaJson)).contains("javax"));
            Assert.assertTrue(new String(Files.readAllBytes(jakartaJson)).contains("jakarta.foo.api"));

            Assert.assertFalse(Files.exists(bind));
            Path jakartaBind = modules.resolve("jakarta/foo/bind/api/main/module.xml");
            Assert.assertTrue(Files.exists(jakartaBind));
            Assert.assertFalse(new String(Files.readAllBytes(jakartaBind)).contains("javax"));
            Assert.assertTrue(new String(Files.readAllBytes(jakartaBind)).contains("jakarta.foo.api"));
            Assert.assertTrue(new String(Files.readAllBytes(jakartaBind)).contains("jakarta.foo.bind.api"));

            TransformedModule jsonTransModule = transformedModules.get("javax.foo.api");
            Assert.assertNotNull(jsonTransModule);
            Assert.assertTrue(jsonTransModule.getName().equals("jakarta.foo.api"));
            Assert.assertTrue(jsonTransModule.getTransformedDependencies().isEmpty());

            TransformedModule bindTransModule = transformedModules.get("javax.foo.bind.api");
            Assert.assertNotNull(bindTransModule);
            Assert.assertTrue(bindTransModule.getName().equals("jakarta.foo.bind.api"));
            Assert.assertTrue(bindTransModule.getTransformedDependencies().get("javax.foo.api").equals("jakarta.foo.api"));
        } finally {
            JBossModulesTransformer.recursiveDelete(modules);
            Assert.assertFalse(Files.exists(modules));
        }
    }

    @Test
    public void testTransformEmptyDirectories() throws Exception {
        Path modules = Files.createTempDirectory("transform-modules-tests");
        try {
            Path jsonDir = Files.createDirectories(modules.resolve("javax/json/api"));
            Path jakartaDir = Files.createDirectories(modules.resolve("jakarta/json/api"));
            Path bindDir = Files.createDirectories(modules.resolve("javax/json/bind/api/main/foo"));
            Path jakartaBindDir = Files.createDirectories(modules.resolve("jakarta/json/bind/api/main/foo"));
            Path fooDir = Files.createDirectories(modules.resolve("foo/json/bind/api"));
            Map<String, TransformedModule> transformedModules = Common.transformModules(modules, null, false, null);
            Assert.assertTrue(transformedModules.isEmpty());
            Assert.assertTrue(Files.list(modules).count() == 2);
            Assert.assertFalse(Files.exists(jsonDir));
            Assert.assertTrue(Files.exists(jakartaDir));
            Assert.assertFalse(Files.exists(bindDir));
            Assert.assertTrue(Files.exists(jakartaBindDir));
            Assert.assertTrue(Files.exists(fooDir));
        } finally {
            JBossModulesTransformer.recursiveDelete(modules);
            Assert.assertFalse(Files.exists(modules));
        }
    }
}
