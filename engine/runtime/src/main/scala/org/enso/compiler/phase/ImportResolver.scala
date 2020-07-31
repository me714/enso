package org.enso.compiler.phase

import org.enso.compiler.Compiler
import org.enso.compiler.core.IR
import org.enso.compiler.pass.analyse.BindingAnalysis
import org.enso.interpreter.runtime.Module

import util.control.Breaks._
import scala.collection.mutable

/**
  * Runs imports resolution. Starts from a given module and then recursively
  * collects all modules that are reachable from it.
  *
  * Each of the reachable modules will be parsed and will have imported modules
  * injected into its metadata. In effect, running this will bring every module
  * that could ever be necessary for the entry point compilation to at least
  * the [[Module.CompilationStage.AFTER_IMPORT_RESOLUTION]] stage.
  *
  * @param compiler the compiler instance for the compiling context.
  */
class ImportResolver(compiler: Compiler) {

  /**
    * Runs the import mapping logic.
    *
    * @param module the entry-point module.
    * @return a list of all modules that need to be compiled in order to run
    *         the program.
    */
  def mapImports(module: Module): List[Module] = {
    val seen: mutable.Set[Module] = mutable.Set()
    var stack: List[Module]       = List(module)
    while (stack.nonEmpty) {
      val current = stack.head
      stack = stack.tail
      breakable {
        if (
          seen.contains(current) || current.getCompilationStage.isAtLeast(
            Module.CompilationStage.AFTER_IMPORT_RESOLUTION
          )
        ) {
          break()
        }
        compiler.ensureParsed(current)
        val ir = current.getIr
        val currentLocal = ir.unsafeGetMetadata(
          BindingAnalysis,
          "Non-parsed module used in ImportResolver"
        )
        val importedModuleNames = ir.imports.collect {
          case imp: IR.Module.Scope.Import.Module => imp.name
        }
        val importedModules = importedModuleNames.flatMap(compiler.getModule)
        // TODO[MK] Remove when No Implicit Prelude
        currentLocal.resolvedImports =
          compiler.context.getTopScope.getBuiltins.getModule :: importedModules
        current.unsafeSetCompilationStage(
          Module.CompilationStage.AFTER_IMPORT_RESOLUTION
        )
        seen += current
        stack = importedModules ++ stack
      }
    }
    seen.toList
  }
}