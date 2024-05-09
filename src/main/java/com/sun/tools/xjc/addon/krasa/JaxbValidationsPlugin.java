package com.sun.tools.xjc.addon.krasa;

import com.sun.codemodel.JFieldVar;
import com.sun.tools.xjc.BadCommandLineException;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.Plugin;
import static com.sun.tools.xjc.addon.krasa.JaxbValidationsAnnotator.escapeRegexp;
import static com.sun.tools.xjc.addon.krasa.JaxbValidationsAnnotator.getIntegerFacet;
import static com.sun.tools.xjc.addon.krasa.JaxbValidationsAnnotator.getMultipleStringFacets;
import static com.sun.tools.xjc.addon.krasa.JaxbValidationsAnnotator.getStringFacet;
import com.sun.tools.xjc.model.CAttributePropertyInfo;
import com.sun.tools.xjc.model.CElementPropertyInfo;
import com.sun.tools.xjc.model.CPropertyInfo;
import com.sun.tools.xjc.model.CValuePropertyInfo;
import com.sun.tools.xjc.outline.ClassOutline;
import com.sun.tools.xjc.outline.FieldOutline;
import com.sun.tools.xjc.outline.Outline;
import com.sun.xml.xsom.XSComponent;
import com.sun.xml.xsom.XSFacet;
import com.sun.xml.xsom.XSParticle;
import com.sun.xml.xsom.XSRestrictionSimpleType;
import com.sun.xml.xsom.XSSimpleType;
import com.sun.xml.xsom.XSType;
import com.sun.xml.xsom.impl.AttributeUseImpl;
import com.sun.xml.xsom.impl.ElementDecl;
import com.sun.xml.xsom.impl.SimpleTypeImpl;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.xml.sax.ErrorHandler;

/**
 *
 * NOTE: fractionDigits fixed attribute cannot be translated into a meaningful Validation.
 *
 * @author Francesco Illuminati
 * @author Vojtěch Krása
 * @author cocorossello
 */
public class JaxbValidationsPlugin extends Plugin {

    static final String NAMESPACE = "http://jaxb.dev.java.net/plugin/code-injector";

    JaxbValidationsOptions.Builder optionsBuilder = JaxbValidationsOptions.builder();
    JaxbValidationsOptions options;

    @Override
    public String getOptionName() {
        return JaxbValidationsArgument.PLUGIN_NAME;
    }

    @Override
    public int parseArgument(Options opt, String[] args, int index)
            throws BadCommandLineException, IOException {
        return JaxbValidationsArgument.parse(optionsBuilder, args[index]);
    }

    @Override
    public List<String> getCustomizationURIs() {
        return Collections.singletonList(NAMESPACE);
    }

    @Override
    public boolean isCustomizationTagName(String nsUri, String localName) {
        return nsUri.equals(NAMESPACE) && localName.equals("code");
    }

    @Override
    public String getUsage() {
        return new StringBuilder()
                .append("  -")
                .append(JaxbValidationsArgument.PLUGIN_OPTION_NAME)
                .append("      :  ")
                .append("inject Bean validation annotations (JSR 303)")
                .append(System.lineSeparator())
                .append("   Options:")
                .append(JaxbValidationsArgument.helpMessageWithPrefix("     "))
                .append(System.lineSeparator())
                .toString();
    }

    @Override
    public boolean run(Outline model, Options opt, ErrorHandler errorHandler) {
        if (opt.verbose) {
            optionsBuilder.verbose(true);
        }

        buildOptions();

        JaxbValidationsLogger logger = new JaxbValidationsLogger(options.isVerbose());

        logger.log(JaxbValidationsArgument.actualOptionValuesString(options, "    "));


        for (ClassOutline co : model.getClasses()) {
            List<CPropertyInfo> properties = co.target.getProperties();

            for (CPropertyInfo property : properties) {
                if (property instanceof CElementPropertyInfo) {
                    processElement((CElementPropertyInfo) property, co, model);

                } else if (property instanceof CAttributePropertyInfo) {
                    processAttribute((CAttributePropertyInfo) property, co, model);

                } else if (property instanceof CValuePropertyInfo) {
                    processAttribute((CValuePropertyInfo) property, co, model);

                }
            }
        }
        return true;
    }

    // must be a separate method to help testing
    void buildOptions() {
        options = optionsBuilder.build();
        optionsBuilder = null;
    }

    /**
     * XS:Element
     */
    public void processElement(CElementPropertyInfo property,
            ClassOutline classOutline, Outline model) {

        XSParticle particle = (XSParticle) property.getSchemaComponent();
        ElementDecl element = (ElementDecl) particle.getTerm();

        String className = classOutline.implClass.name();
        String propertyName = property.getName(false);

        int minOccurs = particle.getMinOccurs().intValue();
        int maxOccurs = particle.getMaxOccurs().intValue();
        boolean required = property.isRequired();
        boolean nillable = element.isNillable();

        JFieldVar field = classOutline.implClass.fields().get(propertyName);
        XSType elementType = element.getType();

        // using https://github.com/jirutka/validator-collection to annotate Lists of primitives
        final XSSimpleType simpleType;
        if (!(elementType instanceof XSSimpleType)) {
            // is a complex type, get the base type
            simpleType = elementType.getBaseType().asSimpleType();
        } else {
            // simple type
            simpleType = elementType.asSimpleType();
        }

        JaxbValidationsAnnotator annotator =
                new JaxbValidationsAnnotator(className, propertyName, field,
                        options.getAnnotationFactory());

        if (options.isNotNullAnnotations() && !nillable &&
                (minOccurs > 0 || required || property.isCollectionRequired()) ) {
            String message = notNullMessage(classOutline, field);
            annotator.addNotNullAnnotation(classOutline, field, message);
        }

        if (property.isCollection()) {
            // add @Valid to all collections
            annotator.addValidAnnotation();

            if (maxOccurs != 0 || minOccurs != 0) {
                // http://www.dimuthu.org/blog/2008/08/18/xml-schema-nillabletrue-vs-minoccurs0/comment-page-1/
                annotator.addSizeAnnotation(minOccurs, maxOccurs, null);
            }
        }

        if (simpleType != null) {
            if ((options.isGenerateStringListAnnotations() && property.isCollection()) ) {
                Integer minLength = getIntegerFacet(simpleType, XSFacet.FACET_MINLENGTH);
                Integer maxLength = getIntegerFacet(simpleType, XSFacet.FACET_MAXLENGTH);
                annotator.addEachSizeAnnotation(minLength, maxLength);

                Integer totalDigits = getIntegerFacet(simpleType, XSFacet.FACET_TOTALDIGITS);
                Integer fractionDigits = getIntegerFacet(simpleType, XSFacet.FACET_FRACTIONDIGITS);
                annotator.addEachDigitsAnnotation(totalDigits, fractionDigits);

                String minInclusive = getStringFacet(simpleType, XSFacet.FACET_MININCLUSIVE);
                String minExclusive = getStringFacet(simpleType, XSFacet.FACET_MINEXCLUSIVE);
                annotator.addEachDecimalMinAnnotation(minInclusive, minExclusive);

                String maxInclusive = getStringFacet(simpleType, XSFacet.FACET_MAXINCLUSIVE);
                String maxExclusive = getStringFacet(simpleType, XSFacet.FACET_MAXEXCLUSIVE);
                annotator.addEachDecimalMaxAnnotation(maxInclusive, maxExclusive);
            }

            processType(simpleType, field, annotator);
        }
    }

    /**
     * Attribute from parent declaration
     */
    void processAttribute(CValuePropertyInfo property,
            ClassOutline clase, Outline model) {
        FieldOutline field = model.getField(property);
        String propertyName = property.getName(false);
        String className = clase.implClass.name();

//        logger.log("Attribute " + propertyName + " added to class " + className);
        XSComponent definition = property.getSchemaComponent();
        SimpleTypeImpl particle = (SimpleTypeImpl) definition;
        XSSimpleType type = particle.asSimpleType();
        JFieldVar var = clase.implClass.fields().get(propertyName);

        if (var != null) {
            JaxbValidationsAnnotator annotator = new JaxbValidationsAnnotator(className, propertyName,
                    var, options.getAnnotationFactory());

            processType(type, var, annotator);
        }
    }

    void processAttribute(CAttributePropertyInfo property,
            ClassOutline clase, Outline model) {
        FieldOutline field = model.getField(property);
        String propertyName = property.getName(false);
        String className = clase.implClass.name();

//        logger.log("Attribute " + propertyName + " added to class " + className);
        XSComponent definition = property.getSchemaComponent();
        AttributeUseImpl particle = (AttributeUseImpl) definition;
        XSSimpleType type = particle.getDecl().getType();
        JFieldVar var = clase.implClass.fields().get(propertyName);

        if (var != null) {
            JaxbValidationsAnnotator annotator = new JaxbValidationsAnnotator(className, propertyName,
                    var, options.getAnnotationFactory());

            if (particle.isRequired()) {
                String message = notNullMessage(clase, var);
                annotator.addNotNullAnnotation(clase, var, message);
            }

            processType(type, var, annotator);
        }
    }


    void processType(XSSimpleType simpleType, JFieldVar field, JaxbValidationsAnnotator annotator) {

        // add @Valid to complext types or custom elements with selected namespace
        String elemNs = simpleType.getTargetNamespace();
        if ((options.getTargetNamespace() != null && elemNs.startsWith(options.getTargetNamespace())) &&
                ((simpleType.isComplexType() || Utils.isCustomType(field))) ) {
            annotator.addValidAnnotation();
        }

        // TODO put this check in Field mng class
        if (field.type().name().equals("String") || field.type().isArray()) {
            Integer maxLength = getIntegerFacet(simpleType, XSFacet.FACET_MAXLENGTH);
            Integer minLength = getIntegerFacet(simpleType, XSFacet.FACET_MINLENGTH);
            Integer length = getIntegerFacet(simpleType, XSFacet.FACET_LENGTH);
            annotator.addSizeAnnotation(minLength, maxLength, length);

            // TODO put this check in Field mng class
            if (options.isJpaAnnotations()) {
                annotator.addJpaColumnAnnotation(maxLength);
            }
        }


        if (Utils.isNumberField(field)) {

            if (!annotator.isAnnotatedWith(
                    options.getAnnotationFactory().getDecimalMinClass())) {

                BigDecimal minInclusive = annotator.getDecimalFacet(simpleType, XSFacet.FACET_MININCLUSIVE);
                if (annotator.isValidValue(minInclusive)) {
                    annotator.addDecimalMinAnnotation(minInclusive, false);
                }

                BigDecimal minExclusive = annotator.getDecimalFacet(simpleType, XSFacet.FACET_MINEXCLUSIVE);
                if (annotator.isValidValue(minExclusive)) {
                    annotator.addDecimalMinAnnotation(minExclusive, true);
                }
            }

            if (!annotator.isAnnotatedWith(
                    options.getAnnotationFactory().getDecimalMaxClass())) {

                BigDecimal maxInclusive = annotator.getDecimalFacet(simpleType, XSFacet.FACET_MAXINCLUSIVE);
                if (annotator.isValidValue(maxInclusive)) {
                    annotator.addDecimalMaxAnnotation(maxInclusive, false);
                }

                BigDecimal maxExclusive = annotator.getDecimalFacet(simpleType, XSFacet.FACET_MAXEXCLUSIVE);
                if (annotator.isValidValue(maxExclusive)) {
                    annotator.addDecimalMaxAnnotation(maxExclusive, true);
                }
            }

            Integer totalDigits = getIntegerFacet(simpleType, XSFacet.FACET_TOTALDIGITS);
            Integer fractionDigits = getIntegerFacet(simpleType, XSFacet.FACET_FRACTIONDIGITS);
            if (totalDigits != null) {
                if (totalDigits == null) {
                    totalDigits = 0;
                }
                if (fractionDigits == null) {
                    fractionDigits = 0;
                }
                annotator.addDigitsAnnotation(totalDigits, fractionDigits);
                if (options.isJpaAnnotations()) {
                    annotator.addJpaColumnStringAnnotation(totalDigits, fractionDigits);
                }
            }
        }

        final String fieldName = field.type().name();
        if ("String".equals(fieldName)) {

            final String pattern = getStringFacet(simpleType,XSFacet.FACET_PATTERN);
            final List<String> patternList = getMultipleStringFacets(simpleType, XSFacet.FACET_PATTERN);

            final String enumeration = getStringFacet(simpleType,XSFacet.FACET_ENUMERATION);
            final List<String> enumerationList = getMultipleStringFacets(simpleType, XSFacet.FACET_ENUMERATION);

            XSSimpleType baseType = simpleType;
            while ((baseType = baseType.getSimpleBaseType()) != null) {
                if (baseType instanceof XSRestrictionSimpleType) {
                    XSRestrictionSimpleType restriction = (XSRestrictionSimpleType) baseType;


                    final String basePattern = getStringFacet(restriction,XSFacet.FACET_PATTERN);
                    patternList.add(basePattern);
                    final List<String> basePatternList = getMultipleStringFacets(restriction, XSFacet.FACET_PATTERN);
                    patternList.addAll(basePatternList);

                    final String baseEnumeration = getStringFacet(restriction,XSFacet.FACET_ENUMERATION);
                    enumerationList.add(baseEnumeration);
                    final List<String> baseEnumerationList = getMultipleStringFacets(restriction, XSFacet.FACET_ENUMERATION);
                    enumerationList.addAll(baseEnumerationList);
                }
            }

            patternList.add(pattern);

            List<String> adjustedPatterns = patternList.stream()
                    .filter(p -> isValidRegexp(p))
                    .map(p -> adjustRegexp(p, false))
                    .distinct()
                    .collect(Collectors.toList());

            // enumuerators can be treated as patterns

            enumerationList.add(enumeration);

            List<String> adjustedEnumerations = enumerationList.stream()
                    .filter(p -> isValidRegexp(p))
                    .map(p -> adjustRegexp(p, true))
                    .distinct()
                    .collect(Collectors.toList());

            adjustedPatterns.addAll(adjustedEnumerations);

            switch (adjustedPatterns.size()) {
                case 0:
                    // do nothing at all
                    break;
                case 1:
                    annotator.annotateSinglePattern(adjustedPatterns.get(0));
                    break;
                default:
                    annotator.addAlternativePatternListAnnotation(adjustedPatterns);
            }
        }
    }

    static String adjustRegexp(String pattern, boolean literal) {
        String replaceRegexp = replaceRegexp(pattern);
        if (literal) {
            replaceRegexp = escapeRegexp(replaceRegexp);
        }
        return replaceRegexp;
    }

    // cxf-codegen fix
    static boolean isValidRegexp(String pattern) {
        return pattern != null && !"\\c+".equals(pattern);
    }

    static String replaceRegexp(String pattern) {
        return pattern
                .replace("\\i", "[_:A-Za-z]")
                .replace("\\c", "[-._:A-Za-z0-9]");
    }

    private String notNullMessage(ClassOutline classOutline, JFieldVar field) {
        final String className = classOutline.implClass.name();
        final Class<? extends Annotation> notNullClass = options.getAnnotationFactory().getNotNullClass();

        String message = null;

        if (options.isNotNullPrefixClassName()) {
            message = String.format("%s.%s {%s.message}",
                    className, field.name(),
                    notNullClass.getName());

        } else if (options.isNotNullPrefixFieldName()) {
            message = String.format("%s {%s.message}",
                    field.name(),
                    notNullClass.getName());

        } else if (options.isNotNullCustomMessage()) {
            message = String.format("{%s.message}",
                    notNullClass.getName());

        } else if (options.getNotNullCustomMessageText() != null) {
            message = options.getNotNullCustomMessageText()
                    .replace("{ClassName}", className)
                    .replace("{FieldName}", field.name());
        }

        return message;
    }


}
