package com.printscript.domain.interfaces

import com.printscript.domain.result.FormatResult
import com.printscript.domain.result.InterpretResult
import com.printscript.domain.result.LintResult
import com.printscript.domain.result.ValidationResult

// aca voy a guardar la logica del lenguaje nom onda me chupa un huevo
// como consigo all es lo mas base como se orquesta todo
interface PrintScriptEngine {

    // ejecuto el codigo
    fun interpret(code: String, version: String, inputs: List<String>?): InterpretResult

    //linteo codigo coin las reglas en formatt jason
    fun lint(code: String, version: String, lintConfigJson: String?): LintResult

    // formateo con reglas configguradas en jason
    fun format(code: String, version: String, formatConfigJson: String?): FormatResult

    fun validate(code: String, version: String): ValidationResult
}
