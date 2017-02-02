/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.support.room.processor

import COMMON
import com.android.support.room.testing.TestInvocation
import com.android.support.room.testing.TestProcessor
import com.android.support.room.vo.Dao
import com.android.support.room.vo.Warning
import com.google.auto.common.MoreElements
import com.google.auto.common.MoreTypes
import com.google.common.truth.Truth
import com.google.testing.compile.CompileTester
import com.google.testing.compile.JavaFileObjects
import com.google.testing.compile.JavaSourcesSubjectFactory
import createVerifierFromEntities
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class DaoProcessorTest(val enableVerification : Boolean) {
    companion object {
        const val DAO_PREFIX = """
            package foo.bar;
            import com.android.support.room.*;
            """
        @Parameterized.Parameters(name = "enableDbVerification={0}")
        @JvmStatic
        fun getParams() = arrayOf(true, false)
    }

    @Test
    fun testNonAbstract() {
        singleDao("@Dao public class MyDao {}") { dao, invocation -> }
                .failsToCompile()
                .withErrorContaining(ProcessorErrors.DAO_MUST_BE_AN_ABSTRACT_CLASS_OR_AN_INTERFACE)
    }

    @Test
    fun testAbstractMethodWithoutQuery() {
        singleDao("""
                @Dao public interface MyDao {
                    int getFoo();
                }
        """) { dao, invocation ->
        }.failsToCompile()
                .withErrorContaining(ProcessorErrors.ABSTRACT_METHOD_IN_DAO_MISSING_ANY_ANNOTATION)
    }

    @Test
    fun testBothAnnotations() {
        singleDao("""
                @Dao public interface MyDao {
                    @Query("select 1")
                    @Insert
                    int getFoo(int x);
                }
        """) { dao, invocation ->
        }.failsToCompile().withErrorContaining(
                ProcessorErrors.CANNOT_USE_MORE_THAN_ONE_DAO_METHOD_ANNOTATION)
    }

    @Test
    fun testAbstractClass() {
        singleDao("""
                @Dao abstract class MyDao {
                    @Query("SELECT uid FROM User")
                    abstract int[] getIds();
                }
                """) { dao, invocation ->
            assertThat(dao.queryMethods.size, `is`(1))
            val method = dao.queryMethods.first()
            assertThat(method.name, `is`("getIds"))
        }.compilesWithoutError()
    }

    @Test
    fun testInterface() {
        singleDao("""
                @Dao interface MyDao {
                    @Query("SELECT uid FROM User")
                    abstract int[] getIds();
                }
                """) { dao, invocation ->
            assertThat(dao.queryMethods.size, `is`(1))
            val method = dao.queryMethods.first()
            assertThat(method.name, `is`("getIds"))
        }.compilesWithoutError()
    }

    @Test
    fun testWithInsertAndQuery() {
        singleDao("""
                @Dao abstract class MyDao {
                    @Query("SELECT uid FROM User")
                    abstract int[] getIds();
                    @Insert
                    abstract void insert(User user);
                }
                """) { dao, invocation ->
            assertThat(dao.queryMethods.size, `is`(1))
            val method = dao.queryMethods.first()
            assertThat(method.name, `is`("getIds"))
            assertThat(dao.insertionMethods.size, `is`(1))
            val insertMethod = dao.insertionMethods.first()
            assertThat(insertMethod.name, `is`("insert"))
        }.compilesWithoutError()
    }

    @Test
    fun skipQueryVerification() {
        singleDao("""
                @Dao @SkipQueryVerification interface MyDao {
                    @Query("SELECT nonExistingField FROM User")
                    abstract int[] getIds();
                }
                """) { dao, invocation ->
            assertThat(dao.queryMethods.size, `is`(1))
            val method = dao.queryMethods.first()
            assertThat(method.name, `is`("getIds"))
        }.compilesWithoutError()
    }

    @Test
    fun suppressedWarnings() {
        singleDao("""
            @SuppressWarnings({"ALL", RoomWarnings.CURSOR_MISMATCH})
            @Dao interface MyDao {
                @Query("SELECT * from user")
                abstract User users();
            }
            """) { dao, invocation ->
            val daoProcessor = DaoProcessor(invocation.context, dao.element, null)
            assertThat(daoProcessor.context.logger
                    .suppressedWarnings, `is`(setOf(Warning.ALL, Warning.CURSOR_MISMATCH)))

            dao.queryMethods.forEach {
                assertThat(QueryMethodProcessor(
                        baseContext = daoProcessor.context,
                        containing = MoreTypes.asDeclared(dao.element.asType()),
                        executableElement = it.element,
                        dbVerifier = null).context.logger.suppressedWarnings,
                        `is`(setOf(Warning.ALL, Warning.CURSOR_MISMATCH)))
            }
        }.compilesWithoutError()
    }

    @Test
    fun suppressedWarningsInheritance() {
        singleDao("""
            @SuppressWarnings(RoomWarnings.CURSOR_MISMATCH)
            @Dao interface MyDao {
                @SuppressWarnings("ALL")
                @Query("SELECT * from user")
                abstract User users();
            }
            """) { dao, invocation ->
            val daoProcessor = DaoProcessor(invocation.context, dao.element, null)
            assertThat(daoProcessor.context.logger
                    .suppressedWarnings, `is`(setOf(Warning.CURSOR_MISMATCH)))

            dao.queryMethods.forEach {
                assertThat(QueryMethodProcessor(
                        baseContext = daoProcessor.context,
                        containing = MoreTypes.asDeclared(dao.element.asType()),
                        executableElement = it.element,
                        dbVerifier = null).context.logger.suppressedWarnings,
                        `is`(setOf(Warning.ALL, Warning.CURSOR_MISMATCH)))
            }
        }.compilesWithoutError()
    }

    fun singleDao(vararg inputs: String, handler: (Dao, TestInvocation) -> Unit):
            CompileTester {
        return Truth.assertAbout(JavaSourcesSubjectFactory.javaSources())
                .that(listOf(JavaFileObjects.forSourceString("foo.bar.MyDao",
                        DAO_PREFIX + inputs.joinToString("\n")
                ), COMMON.USER))
                .processedWith(TestProcessor.builder()
                        .forAnnotations(com.android.support.room.Dao::class,
                                com.android.support.room.Entity::class)
                        .nextRunHandler { invocation ->
                            val dao = invocation.roundEnv
                                    .getElementsAnnotatedWith(
                                            com.android.support.room.Dao::class.java)
                                    .first()
                            val dbVerifier = if (enableVerification) {
                                createVerifierFromEntities(invocation)
                            } else {
                                null
                            }
                            val parser = DaoProcessor(invocation.context,
                                    MoreElements.asType(dao), dbVerifier)

                            val parsedDao = parser.process()
                            handler(parsedDao, invocation)
                            true
                        }
                        .build())
    }
}
