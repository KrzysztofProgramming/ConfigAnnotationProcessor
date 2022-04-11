package app.processors;

import app.annotations.ConfigYml;
import app.utils.*;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.*;
import java.util.stream.Collectors;


@SupportedAnnotationTypes("app.annotations.ConfigYml")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class ConfigProcessor extends AbstractProcessor {
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "ConfigYml annotation processor launched!");
        Set<? extends Element> elements = roundEnv.getElementsAnnotatedWith(ConfigYml.class);
        if(!elements.stream().allMatch(element -> element.getKind()==ElementKind.CLASS)){
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "ConfigYml annotation may only be used to classes");
            return false;
        }

        elements.forEach(anyElement -> {
            TypeElement element = (TypeElement) anyElement;
            if(!hasDefaultConstructor(element)){
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, element.getQualifiedName() + " must have default constructor");
                return;
            }
            if(!validateFieldsNames(element)){
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, element.getQualifiedName() + " has invalid field(s) name(s)!");
                return;
            }

            PackageElement packageElement = null;
            if(element.getEnclosingElement() instanceof PackageElement)
                packageElement = (PackageElement) element.getEnclosingElement();
            try {
                JavaFileObject fileObject = processingEnv.getFiler().createSourceFile(
                        element.getSimpleName() + "Serializator");
                ClassDeclaration classDeclaration = new ClassDeclaration(
                        packageElement==null ? "" : packageElement.getQualifiedName().toString(),
                        Arrays.asList("java.util.*", "org.bukkit.configuration.ConfigurationSection",
                                "org.bukkit.configuration.file.YamlConfiguration"),
                        element.getSimpleName() + "Serializator",
                        new Fields(),
                        new Constructors(),
                        new Methods(
                                new MethodDeclaration(Modifiers.PUBLIC_MODIFIER,
                                        "Map<String, Object>",
                                        "serialize", true,
                                        new Arguments(
                                                new ArgumentDeclaration(
                                                        element.getSimpleName().toString(),
                                                        "toSerialize"
                                                )
                                        ),
                                        generateSerializeMethodBody(element, "toSerialize")
                                        ),
                                new MethodDeclaration(Modifiers.PUBLIC_MODIFIER,
                                        element.getSimpleName().toString(),
                                        "deserialize", true,
                                        new Arguments(
                                                new ArgumentDeclaration(
                                                        "Map<String, Object>",
                                                        "map"
                                                )
                                        ),
                                        generateDeserializeMethodBody(element, "map")
                                        ),
                                new MethodDeclaration(Modifiers.PUBLIC_MODIFIER
                                        ,"void", "injectTo", true,
                                        new Arguments(
                                                new ArgumentDeclaration(
                                                        element.getSimpleName().toString(),
                                                        "toInject"
                                                ),
                                                new ArgumentDeclaration(
                                                        "Map<String, Object>",
                                                        "map"
                                                )
                                        ),
                                        "injectTo(toInject, map, null, null);"
                                ),
                                new MethodDeclaration(Modifiers.PUBLIC_MODIFIER,
                                        "void", "injectTo", true,
                                        new Arguments(
                                                new ArgumentDeclaration(
                                                        element.getSimpleName().toString(),
                                                        "toInject"
                                                ),
                                                new ArgumentDeclaration(
                                                        "Map<String, Object>",
                                                        "map"
                                                ),
                                                new ArgumentDeclaration(
                                                        "Map<String, Object>",
                                                        "defaultMap"
                                                ),
                                                new ArgumentDeclaration(
                                                        "YamlConfiguration",
                                                        "yml"
                                                )
                                        ),
                                        generateInjectMethod(element, "toInject", "map", "defaultMap")
                                        )
                        )
                );
                Writer writer = fileObject.openWriter();
                writer.write(classDeclaration.toString());
                writer.close();
            } catch (IOException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Can't generate file for class: " + element.getQualifiedName());
            }
        });
        return false;
    }

    private String generateSerializeMethodBody(TypeElement typeElement, String variableName){
        StringBuilder builder = new StringBuilder();
        builder.append("Map<String, Object> map = new HashMap<>();\n");

        List<String> fieldNames = filterFields(typeElement.getEnclosedElements()).stream()
                .map(element -> element.getSimpleName().toString()).collect(Collectors.toList());

        fieldNames.forEach(name-> this.groupToMap(name, 0, builder, "map", variableName));
        builder.append("return map;");
        return builder.toString();
    }

    private int mapCounter = 0;
    private Map<String, String> mapNames = new HashMap<>();

    private void groupToMap(String fullName, int position, StringBuilder builder, String mapName, String variableName){
        if(!fullName.contains("_")){
            builder.append(mapName).append(".").append("put(\"").append(fullName).append("\", ").append(variableName)
                    .append(".").append(fullName).append(");\n");
            return;
        }
        groupToMapRec(fullName, position, builder, mapName, variableName);
    }

    private void groupToMapRec(String fullName, int position, StringBuilder builder, String mapName, String variableName){
        String[] temp = splitOnNChar(position + 1, '_', fullName);
        String currentName = temp[1];
        String currentKey = temp[0];

        String newMapName = mapNames.get(currentKey);
        if(newMapName == null){
            newMapName = "map" + (mapCounter++);
            mapNames.put(currentKey, newMapName);
            String shortenKey = currentKey.substring(Math.max(currentKey.lastIndexOf('_') + 1, 0));

            builder.append("Map<String, Object> ").append(newMapName).append(" = ").append("new HashMap<>();\n")
                    .append(mapName).append(".").append("put(\"").append(shortenKey).append("\", ").append(newMapName)
                    .append(");\n");
        }

        if(!currentName.contains("_")){
            builder.append(newMapName).append(".").append("put(\"").append(currentName).append("\", ").append(variableName)
                    .append(".").append(fullName).append(");\n");
            return;
        }

        groupToMapRec(fullName, position + 1, builder, newMapName, variableName);
    }

    public static String[] splitOnNChar(int n, char c, String phrase){
        StringBuilder builder1 = new StringBuilder();
        StringBuilder builder2 = new StringBuilder();

        boolean split = false;
        int counter = 0;

        for(int i=0; i < phrase.length(); i++){
            if(split){
                builder2.append(phrase.charAt(i));
            }
            else{
                if(phrase.charAt(i) == c){
                    counter++;
                    if(counter == n){
                        split = true;
                        continue;
                    }
                }
                builder1.append(phrase.charAt(i));
            }
        }
        return new String[]{builder1.toString(), builder2.toString()};
    }

    private String generateDeserializeMethodBody(TypeElement typeElement, String variableName){
        StringBuilder builder = new StringBuilder();
        String className = typeElement.getSimpleName().toString();
        builder.append(className).append(" toCreate = new ").append(className).append("();\n");
        builder.append("injectTo(toCreate, ").append(variableName).append(");\n");
        builder.append("return ").append("toCreate").append(";");
        return builder.toString();
    }

    private String generateInjectMethod(TypeElement typeElement, String toInjectName, String mapName, String defaultMapName){
        StringBuilder builder = new StringBuilder();
        filterFields(typeElement.getEnclosedElements())
                .forEach(element -> {
                    readFromMap(element, builder, mapName, toInjectName, defaultMapName);
                });

        return builder.toString();
    }

    private final int FROM_MAP = 0;
    private final int FROM_CONFIGURATION = 1;

    private void readFromMap(Element element, StringBuilder builder, String mapName, String toInjectName, String defaultMapName){
        String fullName = element.getSimpleName().toString();
        builder.append("try{");

        builder.append(toInjectName).append(".").append(fullName);
        readFromMapSingle(element, builder, mapName, FROM_CONFIGURATION);

        builder.append("}catch(ClassCastException e){\n");
        builder.append("System.out.println(\"").append(fullName.replaceAll("_", "."))
                .append(" path has wrong value\");\n");
        builder.append("if(").append(defaultMapName).append(" != null").append("){\n");


        builder.append(element.asType().toString()).append(" temp");
        readFromMapSingle(element, builder, defaultMapName, FROM_MAP);

        builder.append(toInjectName).append(".").append(fullName);
        if(element.asType().toString().equals("java.lang.String")){
            builder.append(" = temp.replaceAll(\"&\", \"§\");\n");

        }
        else{
            builder.append(" = temp;\n");
        }

        builder.append("yml.set(\"").append(fullName.replaceAll("_",".")).append("\", temp);\n");
        builder.append("}}\n");
    }

    private void readFromMapSingle(Element element, StringBuilder builder, String mapName, int fromOption){
        String fullName = element.getSimpleName().toString();

        builder.append(" = ((").append(element.asType().toString())
                .append(") ");

        if(!fullName.contains("_")){
            builder.append(mapName);
            directlyReadFromMap(element, fullName, builder, fromOption);
            return;
        }

        if(fromOption == FROM_MAP) {
            for (int i = 0; i < countCharacters('_', fullName); i++) {
                builder.append("((Map<String, Object>)");
            }
        }
        else{
            for (int i = 0; i < countCharacters('_', fullName); i++) {
                builder.append("((ConfigurationSection)");
            }
        }
        builder.append(mapName);
        readFromMapSingleRec(element, 0, builder, fromOption);
    }

    private void addReplacingIfNecessary(StringBuilder builder, Element element){
        if(element.asType().toString().equals("java.lang.String")) {
            builder.append(".replaceAll(\"&\", \"§\");\n");
            return;
        }
        builder.append(";\n");
    }

   private void readFromMapSingleRec(Element element, int position, StringBuilder builder, int fromOption){
        String fullName = element.getSimpleName().toString();
        String[] split = splitOnNChar(position + 1, '_', fullName);
        String currentName = split[1];
        String currentKey = split[0];
        String shortenKey = currentKey.substring(Math.max(currentKey.lastIndexOf('_') + 1, 0));

        if(fromOption == FROM_MAP){
            builder.append(".get(\"").append(shortenKey).append("\"))");
        }
        else{
            builder.append(".get(\"").append(shortenKey).append("\"))").append(".getValues(true)");
        }
        if(!currentName.contains("_")){
            directlyReadFromMap(element, currentName, builder, fromOption);
            return;
        }

        readFromMapSingleRec(element, position + 1, builder, fromOption);

   }

   private void directlyReadFromMap(Element element, String name, StringBuilder builder, int fromOption){
       builder.append(".get(\"").append(name).append("\"))");
       if(fromOption == FROM_CONFIGURATION) addReplacingIfNecessary(builder, element);
       else builder.append(";\n");
   }

   private int countCharacters(char c, String phrase){
        int counter = 0;
        for(int i=0; i<phrase.length(); i++){
            if(phrase.charAt(i) == c)
                counter++;
        }
        return counter;
   }

    private boolean hasDefaultConstructor(TypeElement classToCheck){
        return classToCheck.getEnclosedElements().stream().filter(element -> element.getKind() == ElementKind.CONSTRUCTOR)
                .anyMatch(constructor -> ((ExecutableElement)constructor).getParameters().isEmpty());
    }

    private boolean validateFieldsNames(TypeElement classElement){
        List<? extends Element> elements = filterFields(classElement.getEnclosedElements());
        Set<String> forbiddenNames = new HashSet<>();
        elements.forEach(element -> {
            int count = countCharacters('_', element.getSimpleName().toString());
            for(int i=0; i < count; i++){
                forbiddenNames.add(splitOnNChar(i + 1, '_', element.getSimpleName().toString())[0]);
            }
        });
        return elements.stream().noneMatch(element -> forbiddenNames.contains(element.getSimpleName().toString()));
    }

    private List<? extends Element> filterFields(List<? extends Element> elements){
        return elements.stream().filter(element -> element.getKind() == ElementKind.FIELD &&
                !element.getModifiers().contains(Modifier.FINAL) &&
                !element.getModifiers().contains(Modifier.PRIVATE) &&
                !element.getModifiers().contains(Modifier.STATIC)).collect(Collectors.toList());
    }
}
