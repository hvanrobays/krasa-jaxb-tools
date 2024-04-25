package com.sun.tools.xjc.addon.krasa;

import java.util.function.BiConsumer;

/**
 *
 * @author Francesco Illuminati <fillumina@gmail.com>
 */
enum Argument {
    targetNamespace((p, v) -> p.targetNamespace = v),
    JSR_349((p,v) -> p.jsr349 = toBoolean(v)),
    generateNotNullAnnotations((p,v) -> p.notNullAnnotations = toBoolean(v)),
    notNullAnnotationsCustomMessages((p,v) -> {
        Boolean b = toBoolean(v);

        if (b != null) {
            p.notNullCustomMessage = b;
        } else {
            if (CustomMessageType.Classname.equalsIgnoreCase(v)) {
                p.notNullCustomMessage = true;
                p.notNullPrefixFieldName = false;
                p.notNullPrefixClassName = true;
            } else if (CustomMessageType.Fieldname.equalsIgnoreCase(v)) {
                p.notNullCustomMessage = true;
                p.notNullPrefixFieldName = true;
                p.notNullPrefixClassName = false;
            } else if (v.length() != 0 &&
                    !v.equalsIgnoreCase("false")) {
                p.notNullCustomMessageText = v;
            }
        }
    }),
    verbose((p,v) -> p.verbose = toBoolean(v)),
    jpa((p,v) -> p.jpaAnnotations = toBoolean(v)),
    generateStringListAnnotations((p,v) -> p.generateStringListAnnotations = toBoolean(v)),
    validationAnnotations((p,v) -> p.annotationFactory = ValidationAnnotation.valueOf(v.toUpperCase())),
    generateServiceValidationAnnotations((p,v) -> p.generateStringListAnnotations = toBoolean(v));

    public static final String PLUGIN_NAME = "XJsr303Annotations";
    public static final String PLUGIN_OPTION_NAME = "-" + PLUGIN_NAME;
    public static final int PLUGIN_OPTION_NAME_LENGHT = PLUGIN_OPTION_NAME.length() + 1;

    /**
     * @return the boolean value of v (no case sensitive) or null otherwise.
     */
    private static Boolean toBoolean(String v) {
        if (v == null) {
            return null;
        }
        String lc = v.toLowerCase();
        if ("true".equals(lc)) {
            return Boolean.TRUE;
        } else if ("false".equals(lc)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private BiConsumer<JaxbValidationsPlugin, String> setter;

    Argument(BiConsumer<JaxbValidationsPlugin, String> setter) {
        this.setter = setter;
    }

    public String getOption() {
        return PLUGIN_OPTION_NAME + ":" + name();
    }

    public String getOptionValue(Object value) {
        return PLUGIN_OPTION_NAME + ":" + name() + "=" + value;
    }

    /** @return 1 if the argument is referring to this plugin, 0 otherwise. */
    public static int parse(JaxbValidationsPlugin plugin, String option) {
        if (option.startsWith(PLUGIN_OPTION_NAME)) {
            int idx = option.indexOf("=");
            if (idx != -1) {
                final String name = option.substring(PLUGIN_OPTION_NAME_LENGHT, idx);
                final String value = option.substring(idx + 1);
                Argument argument = Argument.valueOf(name);
                argument.setter.accept(plugin, value);
            } else if (option.length() > PLUGIN_OPTION_NAME_LENGHT) {
                final String name = option.substring(PLUGIN_OPTION_NAME_LENGHT);
                Argument argument = Argument.valueOf(name);
                argument.setter.accept(plugin, "true");
            }
            return 1;
        }
        return 0;
    }

}
