/*
 * Copyright 2008-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kordamp.gradle.clirr

import net.sf.clirr.core.Checker
import net.sf.clirr.core.CheckerException
import net.sf.clirr.core.ClassFilter
import net.sf.clirr.core.internal.bcel.BcelTypeArrayBuilder
import net.sf.clirr.core.spi.JavaType
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.kordamp.gradle.clirr.reporters.CountReporter
import org.kordamp.gradle.clirr.reporters.HtmlReporter
import org.kordamp.gradle.clirr.reporters.Reporter
import org.kordamp.gradle.clirr.reporters.XmlReporter
import org.yaml.snakeyaml.Yaml
import uber.org.apache.bcel.classfile.JavaClass

import static net.sf.clirr.core.internal.ClassLoaderUtil.createClassLoader

class ClirrTask extends DefaultTask {
    public static final String REPORT_NAME = 'compatibility-report'

    @InputFiles
    FileCollection newClasspath;

    @InputFiles
    FileCollection newFiles

    @OutputFile
    File xmlReport

    @OutputFile
    File htmlReport

    ClirrTask() {
        xmlReport = new File("${project.clirr.reportsDir}/${REPORT_NAME}.xml")
        htmlReport = new File("${project.clirr.reportsDir}/${REPORT_NAME}.html")
    }

    @TaskAction
    void run() {
        project.clirr.reportsDir.mkdirs()

        if (!project.extensions.clirr.enabled) {
            logger.info("clirr was disabled for project ${project.name}")
            return
        }

        Checker checker = new Checker()
        JavaType[] origClasses = createClassSet(project.extensions.clirr.baseFiles as File[])
        JavaType[] newClasses = createClassSet((newClasspath + newFiles) as File[])

        Map map = loadExcludeFilter(project.clirr.excludeFilter)

        BufferedListener bufferedListener = new BufferedListener(
            map.differenceTypes ?: [],
            map.packages ?: [],
            map.classes ?: [],
            map.members ?: [:]
        );
        checker.addDiffListener(bufferedListener)

        try {
            checker.reportDiffs(origClasses, newClasses)
        } catch (CheckerException ex) {
            logger.error("Error executing 'clirr' task. ${ex}")
            if (project.clirr.failOnException) {
                throw new GradleException("Can't execute 'clirr' task", ex)
            }
        } catch (Exception e) {
            logger.error("Error executing 'clirr' task. ${e}")
            if (project.clirr.failOnException) {
                throw new GradleException("Can't execute 'clirr' task", e)
            }
        }

        Reporter reporter = new HtmlReporter(project, new FileWriter(htmlReport))
        reporter.report(bufferedListener.differences)

        reporter = new XmlReporter(project, new FileWriter(xmlReport))
        reporter.report(bufferedListener.differences)

        final CountReporter counter = new CountReporter()
        counter.report(bufferedListener.differences)

        if (counter.srcErrors > 0 || counter.srcWarnings > 0 || counter.srcInfos) {
            println("""
            Clirr Report
            ------------
            Infos:    ${counter.srcInfos}
            Warnings: ${counter.srcWarnings}
            Errors:   ${counter.srcErrors}
            """.stripIndent(12))
            println("Please review ${htmlReport.canonicalPath} for more information")
        }

        if (project.clirr.failOnErrors) {
            if (counter.srcErrors > 0) {
                throw new GradleException("There are several compatibility issues.\nPlease check ${htmlReport.canonicalPath} for more information")
            }
        }
    }

    private Map loadExcludeFilter(File excludeFilter) {
        if (!excludeFilter) return [:];
        Yaml yaml = new Yaml()
        def data = yaml.load(new FileInputStream(excludeFilter))
        return data as Map
    }

    private JavaType[] createClassSet(File[] files) {
        final ClassLoader classLoader = createClassLoader(files as String[])
        return BcelTypeArrayBuilder.createClassSet(files, classLoader, new ClassSelector())
    }

    private static class ClassSelector implements ClassFilter {
        @Override
        boolean isSelected(final JavaClass javaClass) {
            return true
        }
    }
}