/*
 * Copyright 2010-2017 JetBrains s.r.o.
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

package org.jetbrains.kotlin.backend.konan

import org.jetbrains.kotlin.backend.common.validateIrModule
import org.jetbrains.kotlin.backend.konan.ir.KonanSymbols
import org.jetbrains.kotlin.backend.konan.ir.ModuleIndex
import org.jetbrains.kotlin.backend.konan.llvm.emitLLVM
import org.jetbrains.kotlin.backend.konan.serialization.KonanSerializationUtil
import org.jetbrains.kotlin.backend.konan.serialization.markBackingFields
import org.jetbrains.kotlin.cli.common.messages.AnalyzerWithCompilerReport
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.config.kotlinSourceRoots
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.psi2ir.Psi2IrConfiguration
import org.jetbrains.kotlin.psi2ir.Psi2IrTranslator

fun runTopLevelPhases(konanConfig: KonanConfig, environment: KotlinCoreEnvironment) {

    val config = konanConfig.configuration

    val targets = konanConfig.targetManager
    if (config.get(KonanConfigKeys.LIST_TARGETS) ?: false) {
        targets.list()
    }

    KonanPhases.config(konanConfig)
    if (config.get(KonanConfigKeys.LIST_PHASES) ?: false) {
        KonanPhases.list()
    }

    if (konanConfig.infoArgsOnly) return

    val context = Context(konanConfig)

    val analyzerWithCompilerReport = AnalyzerWithCompilerReport(context.messageCollector,
            environment.configuration.languageVersionSettings)

    val phaser = PhaseManager(context, null)

    phaser.phase(KonanPhase.FRONTEND) {
        // Build AST and binding info.
        analyzerWithCompilerReport.analyzeAndReport(environment.getSourceFiles()) {
            TopDownAnalyzerFacadeForKonan.analyzeFiles(environment.getSourceFiles(), konanConfig)
        }
        if (analyzerWithCompilerReport.hasErrors()) {
            throw KonanCompilationException()
        }
        context.moduleDescriptor = analyzerWithCompilerReport.analysisResult.moduleDescriptor
    }

    val bindingContext = analyzerWithCompilerReport.analysisResult.bindingContext

    phaser.phase(KonanPhase.PSI_TO_IR) {
        // Translate AST to high level IR.
        val translator = Psi2IrTranslator(context.config.configuration.languageVersionSettings,
                Psi2IrConfiguration(false))
        val generatorContext = translator.createGeneratorContext(context.moduleDescriptor, bindingContext)
        @Suppress("DEPRECATION")
        context.psi2IrGeneratorContext = generatorContext

        val symbols = KonanSymbols(context, generatorContext.symbolTable, generatorContext.symbolTable.lazyWrapper)

        val module = translator.generateModuleFragment(generatorContext, environment.getSourceFiles())

        context.irModule = module
        context.ir.symbols = symbols

//        validateIrModule(context, module)
    }
    phaser.phase(KonanPhase.GEN_SYNTHETIC_FIELDS) {
        markBackingFields(context)
    }
    phaser.phase(KonanPhase.SERIALIZER) {
        val serializer = KonanSerializationUtil(context, context.config.configuration.get(CommonConfigurationKeys.METADATA_VERSION)!!)
        context.serializedLinkData = 
            serializer.serializeModule(context.moduleDescriptor)
    }
    phaser.phase(KonanPhase.BACKEND) {
        phaser.phase(KonanPhase.LOWER) {
            KonanLower(context, phaser).lower()
//            validateIrModule(context, context.ir.irModule) // Temporarily disabled until moving to new IR finished.
            context.ir.moduleIndexForCodegen = ModuleIndex(context.ir.irModule)
        }
        phaser.phase(KonanPhase.BITCODE) {
            emitLLVM(context, phaser)
            produceOutput(context, phaser)
        }
        // We always verify bitcode to prevent hard to debug bugs.
        context.verifyBitCode()

        if (context.shouldPrintBitCode()) {
            context.printBitCode()
        }
    }

    phaser.phase(KonanPhase.LINK_STAGE) {
        LinkStage(context, phaser).linkStage()
    }
}

