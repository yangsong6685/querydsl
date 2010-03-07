/*
 * Copyright (c) 2009 Mysema Ltd.
 * All rights reserved.
 * 
 */
package com.mysema.query.codegen;

import static com.mysema.util.Symbols.*;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;

import net.jcip.annotations.Immutable;

import org.apache.commons.collections15.Transformer;
import org.apache.commons.lang.StringEscapeUtils;

import com.mysema.commons.lang.Assert;
import com.mysema.query.types.custom.Custom;
import com.mysema.query.types.expr.Expr;
import com.mysema.query.types.path.PComparable;
import com.mysema.query.types.path.PDate;
import com.mysema.query.types.path.PDateTime;
import com.mysema.query.types.path.PEntity;
import com.mysema.query.types.path.PNumber;
import com.mysema.query.types.path.PTime;
import com.mysema.query.types.path.Path;
import com.mysema.query.types.path.PathMetadataFactory;
import com.mysema.util.CodeWriter;

/**
 * EntitySerializer is a Serializer implementation for entity types
 * 
 * @author tiwe
 *
 */
@Immutable
public class EntitySerializer implements Serializer{

    private static final String PATH_METADATA = "PathMetadata<?> metadata";
    
    private final TypeMappings typeMappings;

    public EntitySerializer(TypeMappings mappings){
        this.typeMappings = Assert.notNull(mappings);
    }
    
    protected void constructors(EntityType model, SerializerConfig config, CodeWriter writer) throws IOException {
        String localName = model.getLocalRawName();
        String genericName = model.getLocalGenericName();
        
        boolean hasEntityFields = model.hasEntityFields();
        String thisOrSuper = hasEntityFields ? THIS : SUPER;
        
        // 1
        constructorsForVariables(writer, model);    

        // 2
        if (!hasEntityFields){
            writer.beginConstructor("PEntity<? extends "+genericName+"> entity");
            writer.line("super(entity.getType(),entity.getMetadata());");
            writer.end();                
        }        
        
        // 3        
        if (hasEntityFields){
            writer.beginConstructor(PATH_METADATA);
            writer.line("this(metadata, metadata.isRoot() ? INITS : PathInits.DEFAULT);");
            writer.end();
        }else{
            if (!localName.equals(genericName)){
                writer.suppressWarnings(UNCHECKED);
            }
            writer.beginConstructor(PATH_METADATA);
            writer.line("super(", localName.equals(genericName) ? EMPTY : "(Class)", localName, ".class, metadata);");
            writer.end();
        }               
        
        // 4
        if (hasEntityFields){
            if (!localName.equals(genericName)){
                writer.suppressWarnings(UNCHECKED);
            }        
            writer.beginConstructor(PATH_METADATA, "PathInits inits");
            writer.line(thisOrSuper, "(", localName.equals(genericName) ? EMPTY : "(Class)", localName, ".class, metadata, inits);");
            writer.end();
        }                         
        
        // 5 
        if (hasEntityFields){            
            writer.beginConstructor("Class<? extends "+genericName+"> type", PATH_METADATA, "PathInits inits");
            writer.line("super(type, metadata, inits);");
            initEntityFields(writer, config, model); 
            writer.end();
        }
        
        
    }
        
    protected void constructorsForVariables(CodeWriter writer, EntityType model) throws IOException {
        String localName = model.getLocalRawName();
        String genericName = model.getLocalGenericName();
        
        boolean hasEntityFields = model.hasEntityFields();
        String thisOrSuper = hasEntityFields ? THIS : SUPER;
        
        if (!localName.equals(genericName)){
            writer.suppressWarnings(UNCHECKED);
        }        
        writer.beginConstructor("String variable");      
        writer.line(thisOrSuper,"(", localName.equals(genericName) ? EMPTY : "(Class)",
                localName, ".class, forVariable(variable)", hasEntityFields ? ", INITS" : EMPTY, ");");
        writer.end();
    }
    
    protected void entityAccessor(Property field, CodeWriter writer) throws IOException {
        String queryType = typeMappings.getPathType(field.getType(), field.getContext(), false);        
        writer.beginMethod(queryType, field.getEscapedName());
        writer.line("if (", field.getEscapedName(), " == null){");
        writer.line("    ", field.getEscapedName(), " = new ", queryType, "(forProperty(\"", field.getName(), "\"));");
        writer.line("}");
        writer.line(RETURN, field.getEscapedName(), SEMICOLON);
        writer.end();
    }
    

    protected void entityField(Property field, SerializerConfig config, CodeWriter writer) throws IOException {
        String queryType = typeMappings.getPathType(field.getType(), field.getContext(), false);        
        if (field.isInherited()){
            writer.line("// inherited");
        }       
        if (config.useEntityAccessors()){
            writer.protectedField(queryType, field.getEscapedName());
        }else{
            writer.publicFinal(queryType, field.getEscapedName());    
        }                
    }


    protected boolean hasOwnEntityProperties(EntityType model){
        if (model.hasEntityFields()){
            for (Property property : model.getProperties()){
                if (!property.isInherited() && property.getType().getCategory() == TypeCategory.ENTITY){
                    return true;
                }
            }    
        }        
        return false;
    }

    protected void initEntityFields(CodeWriter writer, SerializerConfig config, EntityType model) throws IOException {        
        EntityType superType = model.getSuperType();
        if (superType != null && superType.hasEntityFields()){
            String superQueryType = typeMappings.getPathType(superType, model, false);
            writer.line("this._super = new " + superQueryType + "(type, metadata, inits);");            
        }
        
        for (Property field : model.getProperties()){            
            if (field.getType().getCategory() == TypeCategory.ENTITY){
                String queryType = typeMappings.getPathType(field.getType(), model, false);                               
                if (!field.isInherited()){          
                    writer.line("this." + field.getEscapedName() + ASSIGN,
                        "inits.isInitialized(\""+field.getName()+"\") ? ",
                        NEW + queryType + "(forProperty(\"" + field.getName() + "\")",
                        field.getType().hasEntityFields() ? (", inits.get(\""+field.getName()+"\")") : EMPTY,
                        ") : null;");
                }else if (!config.useEntityAccessors()){
                    writer.line("this.", field.getEscapedName(), ASSIGN, "_super.", field.getEscapedName(), SEMICOLON);
                }   
                
            }else if (field.isInherited() && superType != null && superType.hasEntityFields()){
                writer.line("this.", field.getEscapedName(), " = _super.", field.getEscapedName(), SEMICOLON);
            }
        }        
    }

    protected void intro(EntityType model, SerializerConfig config, CodeWriter writer) throws IOException {                
        introPackage(writer, model);        
        introImports(writer, config, model);
        writer.nl();
        
        introJavadoc(writer, model);        
        introClassHeader(writer, model);        
        
        introFactoryMethods(writer, model);   
        introInits(writer, model);
        if (config.createDefaultVariable()){
            introDefaultInstance(writer, model);    
        }           
        if (model.getSuperType() != null){
            introSuper(writer, model);    
        }        
    }

    @SuppressWarnings(UNCHECKED)
    protected void introClassHeader(CodeWriter writer, EntityType model) throws IOException {
        String queryType = typeMappings.getPathType(model, model, true);
        String localName = model.getLocalGenericName();
        
        TypeCategory category = model.getOriginalCategory();
        Class<? extends Path> pathType;
        switch(category){
            case COMPARABLE : pathType = PComparable.class; break;
            case DATE: pathType = PDate.class; break;
            case DATETIME: pathType = PDateTime.class; break;
            case TIME: pathType = PTime.class; break;
            case NUMERIC: pathType = PNumber.class; break;
            default : pathType = PEntity.class;
        }        
        writer.suppressWarnings(SERIAL);        
        for (Annotation annotation : model.getAnnotations()){
            writer.annotation(annotation);
        }        
        writer.beginClass(queryType, pathType.getSimpleName() + "<" + localName + ">");
    }

    protected void introDefaultInstance(CodeWriter writer, EntityType model) throws IOException {
        String simpleName = model.getUncapSimpleName();
        String queryType = typeMappings.getPathType(model, model, true);
        
        writer.publicStaticFinal(queryType, simpleName, NEW + queryType + "(\"" + simpleName + "\")");
    }

    protected void introFactoryMethods(CodeWriter writer, final EntityType model) throws IOException {
        String localName = model.getLocalRawName();
        String genericName = model.getLocalGenericName();
        
        for (Constructor c : model.getConstructors()){
            // begin
            if (!localName.equals(genericName)){
                writer.suppressWarnings(UNCHECKED);
            }            
            writer.beginStaticMethod("EConstructor<" + genericName + ">", "create", c.getParameters(), new Transformer<Parameter, String>(){
                @Override
                public String transform(Parameter p) {
                    return typeMappings.getExprType(p.getType(), model, false, false, true) + SPACE + p.getName();
                }                
            });
            
            // body
            writer.beginLine("return new EConstructor<" + genericName + ">(");
            if (!localName.equals(genericName)){
                writer.append("(Class)");
            }
            writer.append(localName + DOT_CLASS);
            writer.append(", new Class[]{");
            boolean first = true;
            for (Parameter p : c.getParameters()){
                if (!first){
                    writer.append(COMMA);
                }
                if (p.getType().getPrimitiveName() != null){
                    writer.append(p.getType().getPrimitiveName()+DOT_CLASS);
                }else{
                    p.getType().appendLocalRawName(model, writer);
                    writer.append(DOT_CLASS);    
                }                
                first = false;
            }
            writer.append("}");
            
            for (Parameter p : c.getParameters()){
                writer.append(COMMA + p.getName());
            }
            
            // end
            writer.append(");\n");
            writer.end();
        }        
    }

    protected void introImports(CodeWriter writer, SerializerConfig config, EntityType model) throws IOException {       
        writer.imports(Path.class.getPackage());
        writer.staticimports(PathMetadataFactory.class);        
        
        if (!model.getConstructors().isEmpty()
                || !model.getMethods().isEmpty()
                || (model.hasLists() && config.useListAccessors()) 
                || (model.hasMaps() && config.useMapAccessors())){
            writer.imports(Expr.class.getPackage());
        }
        
        if (!model.getMethods().isEmpty()){
            writer.imports(Custom.class.getPackage());
        }
    }

    protected void introInits(CodeWriter writer, EntityType model) throws IOException {
        if (model.hasEntityFields()){
            List<String> inits = new ArrayList<String>();
            for (Property property : model.getProperties()){
                if (property.getType().getCategory() == TypeCategory.ENTITY){
                    for (String init : property.getInits()){
                        inits.add(property.getEscapedName() + DOT + init);    
                    }   
                }
            }            
            if (!inits.isEmpty()){
                inits.add(0, STAR);
                writer.privateStaticFinal("PathInits", "INITS", "new PathInits(" + writer.join(QUOTE, QUOTE, inits) + ")"); 
            }else{
                writer.privateStaticFinal("PathInits", "INITS", "PathInits.DIRECT");
            }
                
        }               
    }

    protected void introJavadoc(CodeWriter writer, EntityType model) throws IOException {
        String simpleName = model.getSimpleName();
        String queryType = model.getPrefix() + simpleName;        
        writer.javadoc(queryType + " is a Querydsl query type for " + simpleName);
    }

    protected void introPackage(CodeWriter writer, EntityType model) throws IOException {
        writer.packageDecl(model.getPackageName());
        writer.nl();
    }
    
    protected void introSuper(CodeWriter writer, EntityType model) throws IOException {
        EntityType superType = model.getSuperType();
        String superQueryType = typeMappings.getPathType(superType, model, false);
        
        if (!superType.hasEntityFields()){
            writer.publicFinal(superQueryType, "_super", NEW + superQueryType + "(this)");    
        }else{
            writer.publicFinal(superQueryType, "_super");    
        }                  
    }  

    protected void listAccessor(Property field, CodeWriter writer) throws IOException {
        String escapedName = field.getEscapedName();
        String queryType = typeMappings.getPathType(field.getParameter(0), field.getContext(), false);

        writer.beginMethod(queryType, escapedName, "int index");
        writer.line(RETURN + escapedName + ".get(index);").end();
        
        writer.beginMethod(queryType, escapedName, "Expr<Integer> index");
        writer.line(RETURN + escapedName +".get(index);").end();
    }

    protected void mapAccessor(Property field, CodeWriter writer) throws IOException {
        String escapedName = field.getEscapedName();
        String queryType = typeMappings.getPathType(field.getParameter(1), field.getContext(), false);
        String keyType = field.getParameter(0).getLocalGenericName(field.getContext(), false);
        String genericKey = field.getParameter(0).getLocalGenericName(field.getContext(), true);
        
        writer.beginMethod(queryType, escapedName, keyType + " key");
        writer.line(RETURN + escapedName + ".get(key);").end();
        
        writer.beginMethod(queryType, escapedName, "Expr<" + genericKey + "> key");
        writer.line(RETURN + escapedName + ".get(key);").end();
    }

    protected void method(final EntityType model, Method method, SerializerConfig config, CodeWriter writer) throws IOException {
        // header
        String type = typeMappings.getExprType(method.getReturnType(), model, false, true, false);
        writer.beginMethod(type, method.getName(), method.getParameters(), new Transformer<Parameter,String>(){
            @Override
            public String transform(Parameter p) {
                return typeMappings.getExprType(p.getType(), model, false, false, true) + SPACE + p.getName();
            }            
        });
        
        // body start
        String customClass = typeMappings.getCustomType(method.getReturnType(), model, true);        
        writer.beginLine(RETURN + customClass + ".create(");
        String fullName = method.getReturnType().getFullName();
        if (!fullName.equals(String.class.getName()) && !fullName.equals(Boolean.class.getName())){
            method.getReturnType().appendLocalRawName(model, writer);
            writer.append(".class, ");
        }        
        writer.append(QUOTE + StringEscapeUtils.escapeJava(method.getTemplate()) + QUOTE);
        writer.append(", this");
        for (Parameter p : method.getParameters()){
            writer.append(COMMA + p.getName());
        }        
        writer.append(");\n");

        // body end
        writer.end();
    }

    protected void outro(EntityType model, CodeWriter writer) throws IOException {
        writer.end();        
    }

    public void serialize(EntityType model, SerializerConfig config, CodeWriter writer) throws IOException{
        intro(model, config, writer);        
        
        // properties
        serializeProperties(model, config, writer);        
        
        // constructors
        constructors(model, config, writer);        

        // methods
        for (Method method : model.getMethods()){
            method(model, method, config, writer);
        }
        
        // property accessors
        for (Property property : model.getProperties()){
            TypeCategory category = property.getType().getCategory();
            if (category == TypeCategory.MAP && config.useMapAccessors()){
                mapAccessor(property, writer);
            }else if (category == TypeCategory.LIST && config.useListAccessors()){
                listAccessor(property, writer);
            }else if (category == TypeCategory.ENTITY && config.useEntityAccessors()){
                entityAccessor(property, writer);
            }
        }
        outro(model, writer);
    }

    protected void serialize(Property field, String type, CodeWriter writer, String factoryMethod, String... args) throws IOException{
        EntityType superType = field.getContext().getSuperType();
        // construct value
        StringBuilder value = new StringBuilder();
        if (field.isInherited() && superType != null){
            if (!superType.hasEntityFields()){
                value.append("_super." + field.getEscapedName());    
            }            
        }else{
            value.append(factoryMethod + "(\"" + field.getName() + QUOTE);
            for (String arg : args){
                value.append(COMMA + arg);
            }        
            value.append(")");    
        }                 
        
        // serialize it
        if (field.isInherited()){
            writer.line("//inherited");
        }        
        if (value.length() > 0){
            writer.publicFinal(type, field.getEscapedName(), value.toString());    
        }else{
            writer.publicFinal(type, field.getEscapedName());
        }        
    }

    private void serializeProperties(EntityType model,  SerializerConfig config, CodeWriter writer) throws IOException {
        for (Property property : model.getProperties()){
            String queryType = typeMappings.getPathType(property.getType(), model, false);
            String localGenericName = property.getType().getLocalGenericName(model, true);
            String localRawName = property.getType().getLocalRawName(model);
            
            switch(property.getType().getCategory()){
            case STRING: 
                serialize(property, queryType, writer, "createString");
                break;
            case BOOLEAN: 
                serialize(property, queryType, writer, "createBoolean"); 
                break;
            case SIMPLE: 
                serialize(property, queryType, writer, "createSimple", localRawName+DOT_CLASS); 
                break;
            case COMPARABLE: 
                serialize(property, queryType, writer, "createComparable", localRawName + DOT_CLASS); 
                break;
            case DATE: 
                serialize(property, queryType, writer, "createDate", localRawName+DOT_CLASS); 
                break;
            case DATETIME: 
                serialize(property, queryType, writer, "createDateTime", localRawName + DOT_CLASS); 
                break;
            case TIME: 
                serialize(property, queryType, writer, "createTime", localRawName+DOT_CLASS); 
                break;
            case NUMERIC: 
                serialize(property, queryType, writer, "createNumber", localRawName +DOT_CLASS);
                break;
            case ARRAY:    
                localGenericName = property.getParameter(0).getLocalGenericName(model, true);
                serialize(property, "PArray<" + localGenericName + ">", writer, "createArray",localRawName+DOT_CLASS);
                break;
            case COLLECTION: 
                localGenericName = property.getParameter(0).getLocalGenericName(model, true);
                localRawName = property.getParameter(0).getLocalRawName(model);
                serialize(property, "PCollection<" + localGenericName + ">", writer, "createCollection",localRawName+DOT_CLASS);
                break;
            case SET: 
                localGenericName = property.getParameter(0).getLocalGenericName(model, true);
                localRawName = property.getParameter(0).getLocalRawName(model);
                serialize(property, "PSet<" + localGenericName + ">", writer, "createSet",localRawName+DOT_CLASS);
                break;
            case MAP:                 
                String genericKey = property.getParameter(0).getLocalGenericName(model, true);
                String genericValue = property.getParameter(1).getLocalGenericName(model, true);
                String genericQueryType = typeMappings.getPathType(property.getParameter(1), model, false);
                String keyType = property.getParameter(0).getLocalRawName(model);
                String valueType = property.getParameter(1).getLocalRawName(model);
                queryType = typeMappings.getPathType(property.getParameter(1), model, true);
                
                serialize(property, "PMap<"+genericKey+COMMA+genericValue+COMMA+genericQueryType+">",
                        writer, "this.<"+genericKey+COMMA+genericValue+COMMA+genericQueryType+">createMap", 
                        keyType+DOT_CLASS, 
                        valueType+DOT_CLASS, 
                        queryType+DOT_CLASS);
                break;
            case LIST:                 
                localGenericName = property.getParameter(0).getLocalGenericName(model, true);                
                genericQueryType = typeMappings.getPathType(property.getParameter(0), model, false);
                localRawName = property.getParameter(0).getLocalRawName(model);
                queryType = typeMappings.getPathType(property.getParameter(0), model, true);
                
                serialize(property, "PList<" + localGenericName+ COMMA + genericQueryType +  ">", writer, "createList", localRawName+DOT_CLASS, queryType +DOT_CLASS);  
                break;
            case ENTITY: 
                entityField(property, config, writer); 
                break;
            }
        }
    }



}
