package domain.interfaces;

import java.util.List;

// aca voy a guardar la logica del lenguaje nom onda me chupa un huevo
// como consigo all es lo mas base como se orquesta todo
public interface PrintScriptEngine {

    // ejecuto el codigo
    InterpretResult interpret(String code, String version, List<String> inputs);

    //linteo codigo coin las reglas en formatt jason
    LintResult lint(String code, String version, String lintConfigJson);

    // formateo con reglas configguradas en jason
    FormatResult format(String code, String version, String formatConfigJson);
}