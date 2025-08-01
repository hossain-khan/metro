// Copyright (C) 2021 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.ClassIds
import dev.zacsweers.metro.compiler.ExitProcessingException
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.ir.transformers.ContributionBindsFunctionsIrTransformer
import dev.zacsweers.metro.compiler.ir.transformers.DependencyGraphTransformer
import dev.zacsweers.metro.compiler.ir.transformers.HintGenerator
import dev.zacsweers.metro.compiler.tracing.trace
import dev.zacsweers.metro.compiler.tracing.traceNested
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.incremental.components.ExpectActualTracker
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

public class MetroIrGenerationExtension(
  private val messageCollector: MessageCollector,
  private val classIds: ClassIds,
  private val options: MetroOptions,
  private val lookupTracker: LookupTracker?,
  private val expectActualTracker: ExpectActualTracker,
) : IrGenerationExtension {

  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    val symbols = Symbols(moduleFragment, pluginContext, classIds, options)
    val context =
      IrMetroContext(
        pluginContext,
        messageCollector,
        symbols,
        options,
        lookupTracker,
        expectActualTracker,
      )

    context(context) { generateInner(moduleFragment) }
  }

  context(context: IrMetroContext)
  private fun generateInner(moduleFragment: IrModuleFragment) {
    try {
      tracer(moduleFragment.name.asString().removePrefix("<").removeSuffix(">"), "Metro compiler")
        .trace { tracer ->
          // First part 1 - transform $$MetroContribution interfaces to add their binds functions
          tracer.traceNested("Transforming Metro contributions") {
            moduleFragment.transform(ContributionBindsFunctionsIrTransformer(context), null)
          }

          // First part 2 - collect all the contributions in this round
          val contributionData = IrContributionData(context)
          tracer.traceNested("Collecting contributions") {
            moduleFragment.accept(IrContributionVisitor(context), contributionData)
          }

          // Second - transform the dependency graphs
          tracer.traceNested("Core transformers") { nestedTracer ->
            val dependencyGraphTransformer =
              DependencyGraphTransformer(
                context,
                contributionData,
                nestedTracer,
                HintGenerator(context, moduleFragment),
              )
            moduleFragment.transform(dependencyGraphTransformer, null)
          }
        }
    } catch (_: ExitProcessingException) {
      // Reported internally
      return
    }
  }
}
