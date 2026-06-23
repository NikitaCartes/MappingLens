import * as monaco from "monaco-editor";

const OPCODES =
  "NOP|ACONST_NULL|ICONST_M1|ICONST_0|ICONST_1|ICONST_2|ICONST_3|ICONST_4|ICONST_5|LCONST_0|LCONST_1|FCONST_0|FCONST_1|FCONST_2|DCONST_0|DCONST_1|BIPUSH|SIPUSH|LDC|LDC_W|LDC2_W|ILOAD|LLOAD|FLOAD|DLOAD|ALOAD|ILOAD_0|ILOAD_1|ILOAD_2|ILOAD_3|LLOAD_0|LLOAD_1|LLOAD_2|LLOAD_3|FLOAD_0|FLOAD_1|FLOAD_2|FLOAD_3|DLOAD_0|DLOAD_1|DLOAD_2|DLOAD_3|ALOAD_0|ALOAD_1|ALOAD_2|ALOAD_3|IALOAD|LALOAD|FALOAD|DALOAD|AALOAD|BALOAD|CALOAD|SALOAD|ISTORE|LSTORE|FSTORE|DSTORE|ASTORE|ISTORE_0|ISTORE_1|ISTORE_2|ISTORE_3|LSTORE_0|LSTORE_1|LSTORE_2|LSTORE_3|FSTORE_0|FSTORE_1|FSTORE_2|FSTORE_3|DSTORE_0|DSTORE_1|DSTORE_2|DSTORE_3|ASTORE_0|ASTORE_1|ASTORE_2|ASTORE_3|IASTORE|LASTORE|FASTORE|DASTORE|AASTORE|BASTORE|CASTORE|SASTORE|POP|POP2|DUP|DUP_X1|DUP_X2|DUP2|DUP2_X1|DUP2_X2|SWAP|IADD|LADD|FADD|DADD|ISUB|LSUB|FSUB|DSUB|IMUL|LMUL|FMUL|DMUL|IDIV|LDIV|FDIV|DDIV|IREM|LREM|FREM|DREM|INEG|LNEG|FNEG|DNEG|ISHL|LSHL|ISHR|LSHR|IUSHR|LUSHR|IAND|LAND|IOR|LOR|IXOR|LXOR|IINC|I2L|I2F|I2D|L2I|L2F|L2D|F2I|F2L|F2D|D2I|D2L|D2F|I2B|I2C|I2S|LCMP|FCMPL|FCMPG|DCMPL|DCMPG|IFEQ|IFNE|IFLT|IFGE|IFGT|IFLE|IF_ICMPEQ|IF_ICMPNE|IF_ICMPLT|IF_ICMPGE|IF_ICMPGT|IF_ICMPLE|IF_ACMPEQ|IF_ACMPNE|GOTO|JSR|RET|TABLESWITCH|LOOKUPSWITCH|IRETURN|LRETURN|FRETURN|DRETURN|ARETURN|RETURN|GETSTATIC|PUTSTATIC|GETFIELD|PUTFIELD|INVOKEVIRTUAL|INVOKESPECIAL|INVOKESTATIC|INVOKEINTERFACE|INVOKEDYNAMIC|NEW|NEWARRAY|ANEWARRAY|ARRAYLENGTH|ATHROW|CHECKCAST|INSTANCEOF|MONITORENTER|MONITOREXIT|WIDE|MULTIANEWARRAY|IFNULL|IFNONNULL";

const DIRECTIVES =
  "MAXSTACK|MAXLOCALS|LINENUMBER|LOCALVARIABLE|FRAME|TRYCATCHBLOCK|FIELD|METHOD|OUTERCLASS|DEPRECATED|SIGNATURE|SOURCEFILE|SOURCEDEBUGINFOEXTENSION|NESTHOST|NESTMEMBERS|PERMITTEDSUBCLASSES|RECORDCOMPONENT|ANNOTATION|PARAMETER|ATTRIBUTE|INNERCLASS";

let registered = false;

/** Registers the ASM-Textifier "bytecode" language, a unified-"diff" highlighter, and a dark theme. */
export function registerLanguages(): void {
  if (registered) return;
  registered = true;

  // Bytecode — ported from mcsrc src/utils/JavaBytecode.ts (ASM Textifier output).
  monaco.languages.register({ id: "bytecode" });
  monaco.languages.setMonarchTokensProvider("bytecode", {
    tokenizer: {
      root: [
        [/\/\/.*$/, "comment"],
        [/"([^"\\]|\\.)*$/, "string.invalid"],
        [/"/, "string", "@string"],
        [/@[a-zA-Z_$][\w$]*/, "annotation"],
        [/\bL\d+\b/, "label"],
        [new RegExp(`\\b(?:${DIRECTIVES})\\b`), "keyword.directive"],
        [new RegExp(`\\b(?:${OPCODES})\\b`), "keyword"],
        [
          /\b(?:public|private|protected|static|final|synchronized|volatile|transient|native|interface|abstract|strictfp|synthetic|annotation|enum|mandated)\b/,
          "keyword",
        ],
        [
          /\b(?:class|extends|implements|version|access|flags|signature|declaration|parameter|handle|kind|arguments|itf)\b/,
          "keyword",
        ],
        [/\b(?:void|boolean|byte|char|short|int|long|float|double)\b/, "type"],
        [/\b0[xX][0-9a-fA-F]+\b/, "number.hex"],
        [/\b\d+\b/, "number"],
        [/L[a-zA-Z0-9_$/]+;/, "type"],
        [/\[[ZBCSIJFD]/, "type"],
        [/\[L[a-zA-Z0-9_$/]+;/, "type"],
        [/[a-zA-Z_$][\w$/]*/, "identifier"],
        [/[{}()[\]]/, "delimiter.bracket"],
        [/[<>]/, "delimiter.angle"],
        [/[:,.]/, "delimiter"],
      ],
      string: [
        [/[^\\"]+/, "string"],
        [/\\./, "string.escape"],
        [/"/, "string", "@pop"],
      ],
    },
  });

  // Unified diff/patch highlighting.
  monaco.languages.register({ id: "diffpatch" });
  monaco.languages.setMonarchTokensProvider("diffpatch", {
    tokenizer: {
      root: [
        [/^(diff|index|---|\+\+\+|new file|deleted file|rename|similarity|old mode|new mode).*$/, "diff.meta"],
        [/^@@.*$/, "diff.hunk"],
        [/^\+.*$/, "diff.add"],
        [/^-.*$/, "diff.del"],
      ],
    },
  });

  monaco.editor.defineTheme("ml-dark", {
    base: "vs-dark",
    inherit: true,
    rules: [
      { token: "diff.add", foreground: "4ec9b0" },
      { token: "diff.del", foreground: "f48771" },
      { token: "diff.hunk", foreground: "569cd6", fontStyle: "bold" },
      { token: "diff.meta", foreground: "808080" },
      { token: "label", foreground: "c08cff" },
      { token: "annotation", foreground: "dcdcaa" },
    ],
    colors: {
      "editor.background": "#0f1115",
    },
  });
}
