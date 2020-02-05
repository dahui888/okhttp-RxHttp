package com.rxhttp.compiler

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import rxhttp.wrapper.annotation.Param
import java.io.IOException
import java.util.*
import javax.annotation.processing.Filer
import javax.lang.model.element.ElementKind
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.Modifier
import javax.lang.model.element.TypeElement
import kotlin.Boolean
import kotlin.Long
import kotlin.String

class ParamsAnnotatedClass {

    private val mElementMap = LinkedHashMap<String, TypeElement>()

    fun add(typeElement: TypeElement) {
        val annotation = typeElement.getAnnotation(Param::class.java)
        val name: String = annotation.methodName
        require(name.isNotEmpty()) {
            String.format("methodName() in @%s for class %s is null or empty! that's not allowed",
                Param::class.java.simpleName, typeElement.qualifiedName.toString())
        }
        mElementMap[name] = typeElement
    }

    @Throws(IOException::class)
    fun getMethodList(filer: Filer?, companionFunList: MutableList<FunSpec>): List<FunSpec> {
        val rxHttp = RxHttpGenerator.r
        val headerBuilderName = ClassName("okhttp3.Headers", "Builder")
        val cacheControlName = ClassName("okhttp3", "CacheControl")
        val paramName = ClassName(RxHttpGenerator.packageName, "Param")
        val noBodyParamName = ClassName(RxHttpGenerator.packageName, "NoBodyParam")
        val formParamName = ClassName(RxHttpGenerator.packageName, "FormParam")
        val jsonParamName = ClassName(RxHttpGenerator.packageName, "JsonParam")
        val jsonArrayParamName = ClassName(RxHttpGenerator.packageName, "JsonArrayParam")
        val cacheModeName = ClassName("rxhttp.wrapper.cahce", "CacheMode")
        val t = TypeVariableName("T")
        val superT = WildcardTypeName.consumerOf(t)
        val classTName = Class::class.asClassName().parameterizedBy(superT)
        val methodList = ArrayList<FunSpec>()
        val methodMap = LinkedHashMap<String, String>()
        methodMap["get"] = "RxHttp_NoBodyParam"
        methodMap["head"] = "RxHttp_NoBodyParam"
        methodMap["postForm"] = "RxHttp_FormParam"
        methodMap["putForm"] = "RxHttp_FormParam"
        methodMap["patchForm"] = "RxHttp_FormParam"
        methodMap["deleteForm"] = "RxHttp_FormParam"
        methodMap["postJson"] = "RxHttp_JsonParam"
        methodMap["putJson"] = "RxHttp_JsonParam"
        methodMap["patchJson"] = "RxHttp_JsonParam"
        methodMap["deleteJson"] = "RxHttp_JsonParam"
        methodMap["postJsonArray"] = "RxHttp_JsonArrayParam"
        methodMap["putJsonArray"] = "RxHttp_JsonArrayParam"
        methodMap["patchJsonArray"] = "RxHttp_JsonArrayParam"
        methodMap["deleteJsonArray"] = "RxHttp_JsonArrayParam"
        var funBuilder: FunSpec.Builder
        for ((key, value) in methodMap) {
            companionFunList.add(
                FunSpec.builder(key)
                    .addAnnotation(JvmStatic::class)
                    .addParameter("url", String::class)
                    .addParameter("formatArgs", Any::class, KModifier.VARARG)
                    .addStatement("return \n    with(%T.%L(format(url, formatArgs)))", paramName, key)
                    .build())
        }
        for ((key, typeElement) in mElementMap) { //根据@Param注解，生成对应的方法及类
            val param = ClassName.bestGuess(typeElement.qualifiedName.toString())
            val rxHttpName = "RxHttp_" + typeElement.simpleName
            val rxhttpParamName = ClassName(RxHttpGenerator.packageName, rxHttpName)
            companionFunList.add(
                FunSpec.builder(key)
                    .addAnnotation(JvmStatic::class)
                    .addParameter("url", String::class)
                    .addParameter("formatArgs", Any::class, KModifier.VARARG)
                    .addStatement("return  \n    %T(%T(format(url, formatArgs)))", rxhttpParamName, param)
                    .build())
            val superclass = typeElement.superclass
            var rxhttpParam: TypeName
            var prefix = "(param as " + param.simpleName + ")."
            when (superclass.toString()) {
                "rxhttp.wrapper.param.FormParam" -> rxhttpParam = ClassName(RxHttpGenerator.packageName, "RxHttp_FormParam")
                "rxhttp.wrapper.param.JsonParam" -> rxhttpParam = ClassName(RxHttpGenerator.packageName, "RxHttp_JsonParam")
                "rxhttp.wrapper.param.NoBodyParam" -> rxhttpParam = ClassName(RxHttpGenerator.packageName, "RxHttp_NoBodyParam")
                else -> {
                    prefix = "param."
                    rxhttpParam = RxHttpGenerator.RXHTTP.parameterizedBy(param, rxhttpParamName)
                }
            }
            val rxHttpParamAnnotationFun = ArrayList<FunSpec>()
            rxHttpParamAnnotationFun.add(
                FunSpec.constructorBuilder()
                    .addParameter("param", param)
                    .callSuperConstructor("param")
                    .build())

            for (enclosedElement in typeElement.enclosedElements) {
                if (enclosedElement !is ExecutableElement
                    || !enclosedElement.getModifiers().contains(Modifier.PUBLIC)//过滤非public修饰符
                    || enclosedElement.getKind() != ElementKind.METHOD//过滤非方法，
                    || enclosedElement.getAnnotation(Override::class.java) != null //过滤重写的方法
                ) continue
                val returnTypeMirror = enclosedElement.returnType
                var returnType = returnTypeMirror.asTypeName()
                if (returnType.toString() == param.toString()) {
                    returnType = rxhttpParamName
                }
                val parameterSpecs = ArrayList<ParameterSpec>()
                val builder = StringBuilder()
                    .append(enclosedElement.getSimpleName().toString())
                    .append("(")
                enclosedElement.parameters.forEach {
                    val parameterSpec = ParameterSpec.get(it)
                    parameterSpecs.add(parameterSpec)
                    builder.append(parameterSpec.name).append(",")
                }
                if (builder.toString().endsWith(",")) {
                    builder.deleteCharAt(builder.length - 1)
                }
                builder.append(")")

                if (enclosedElement.isVarArgs) { //处理可变参数
                    if (enclosedElement.parameters.size == 1) {
                        builder.insert(builder.indexOf("(") + 1, "*")
                    } else if (enclosedElement.parameters.size > 1) {
                        val indexOf = builder.lastIndexOf(",")
                        builder.insert(indexOf + 1, "*")
                    }
                }
                funBuilder = enclosedElement.toFunSpecBuilder()
                if (returnType === rxhttpParamName) {
                    funBuilder.addStatement(prefix + builder, param)
                        .addStatement("return this")
                } else if (returnType.toString() == "void") {
                    funBuilder.addStatement(prefix + builder)
                } else {
                    funBuilder.addStatement("return $prefix$builder", param)
                }
                funBuilder.returns(returnType)
                rxHttpParamAnnotationFun.add(funBuilder.build())
            }
            val rxHttpPostEncryptFormParamSpec = TypeSpec.classBuilder(rxHttpName)
                .addKdoc("Github" +
                    "\nhttps://github.com/liujingxing/RxHttp" +
                    "\nhttps://github.com/liujingxing/RxLife\n")
                .addModifiers(KModifier.PUBLIC, KModifier.OPEN)
                .superclass(rxhttpParam)
                .addFunctions(rxHttpParamAnnotationFun)
                .build()
            FileSpec.builder(RxHttpGenerator.packageName, rxHttpName)
                .addType(rxHttpPostEncryptFormParamSpec)
                .build().writeTo(filer!!)
        }

        companionFunList.add(
            FunSpec.builder("with")
                .addAnnotation(JvmStatic::class)
                .addParameter("noBodyParam", noBodyParamName)
                .addStatement("return %L(noBodyParam)", "RxHttp_NoBodyParam")
                .build())
        companionFunList.add(
            FunSpec.builder("with")
                .addAnnotation(JvmStatic::class)
                .addParameter("formParam", formParamName)
                .addStatement("return %L(formParam)", "RxHttp_FormParam")
                .build())
        companionFunList.add(
            FunSpec.builder("with")
                .addAnnotation(JvmStatic::class)
                .addParameter("jsonParam", jsonParamName)
                .addStatement("return %L(jsonParam)", "RxHttp_JsonParam")
                .build())
        companionFunList.add(
            FunSpec.builder("with")
                .addAnnotation(JvmStatic::class)
                .addParameter("jsonArrayParam", jsonArrayParamName)
                .addStatement("return %L(jsonArrayParam)", "RxHttp_JsonArrayParam")
                .build())
        methodList.add(
            FunSpec.builder("setUrl")
                .addParameter("url", String::class)
                .addStatement("param.setUrl(url)")
                .addStatement("return this as R")
                .returns(rxHttp)
                .build())
        methodList.add(
            FunSpec.builder("addHeader")
                .addParameter("line", String::class)
                .addStatement("param.addHeader(line)")
                .addStatement("return this as R")
                .returns(rxHttp)
                .build())
        methodList.add(
            FunSpec.builder("addHeader")
                .addParameter("line", String::class)
                .addParameter("isAdd", Boolean::class)
                .beginControlFlow("if(isAdd)")
                .addStatement("param.addHeader(line)")
                .endControlFlow()
                .addStatement("return this as R")
                .returns(rxHttp)
                .build())
        methodList.add(
            FunSpec.builder("addHeader")
                .addParameter("key", String::class)
                .addParameter("value", String::class)
                .addStatement("param.addHeader(key,value)")
                .addStatement("return this as R")
                .returns(rxHttp)
                .build())
        methodList.add(
            FunSpec.builder("addHeader")
                .addParameter("key", String::class)
                .addParameter("value", String::class)
                .addParameter("isAdd", Boolean::class)
                .beginControlFlow("if(isAdd)")
                .addStatement("param.addHeader(key,value)")
                .endControlFlow()
                .addStatement("return this as R")
                .returns(rxHttp)
                .build())
        methodList.add(
            FunSpec.builder("setHeader")
                .addParameter("key", String::class)
                .addParameter("value", String::class)
                .addStatement("param.setHeader(key,value)")
                .addStatement("return this as R")
                .returns(rxHttp)
                .build())
        methodList.add(
            FunSpec.builder("setRangeHeader")
                .addParameter("startIndex", Long::class)
                .addStatement("param.setRangeHeader(startIndex)")
                .addStatement("return this as R")
                .returns(rxHttp)
                .build())
        methodList.add(
            FunSpec.builder("setRangeHeader")
                .addParameter("startIndex", Long::class)
                .addParameter("endIndex", Long::class)
                .addStatement("param.setRangeHeader(startIndex,endIndex)")
                .addStatement("return this as R")
                .returns(rxHttp)
                .build())
        methodList.add(
            FunSpec.builder("removeAllHeader")
                .addParameter("key", String::class)
                .addStatement("param.removeAllHeader(key)")
                .addStatement("return this as R")
                .returns(rxHttp)
                .build())
        methodList.add(
            FunSpec.builder("setHeadersBuilder")
                .addParameter("builder", headerBuilderName)
                .addStatement("param.setHeadersBuilder(builder)")
                .addStatement("return this as R")
                .returns(rxHttp)
                .build())
        methodList.add(
            FunSpec.builder("setAssemblyEnabled")
                .addKdoc("设置单个接口是否需要添加公共参数," +
                    "\n即是否回调通过 [setOnParamAssembly] 方法设置的接口,默认为true\n")
                .addParameter("enabled", Boolean::class)
                .addStatement("param.setAssemblyEnabled(enabled)")
                .addStatement("return this as R")
                .returns(rxHttp)
                .build())

        val annoDeprecated = AnnotationSpec.builder(kotlin.Deprecated::class)
            .addMember(
                "\n\"please user [setDecoderEnabled] instead\""
                    + "\n,ReplaceWith(\"setDecoderEnabled(enabled)\", \"RxHttp.setDecoderEnabled\")")
            .build()

        methodList.add(
            FunSpec.builder("setConverterEnabled")
                .addAnnotation(annoDeprecated)
                .addKdoc("@deprecated please user [setDecoderEnabled] instead\n")
                .addParameter("enabled", Boolean::class)
                .addStatement("return setDecoderEnabled(enabled)")
                .returns(rxHttp)
                .build())
        methodList.add(
            FunSpec.builder("setDecoderEnabled")
                .addKdoc("设置单个接口是否需要对Http返回的数据进行解码/解密," +
                    "\n即是否回调通过 [setResultDecoder] 方法设置的接口,默认为true\n")
                .addParameter("enabled", Boolean::class)
                .addStatement("param.addHeader(%T.DATA_DECRYPT,enabled.toString())", paramName)
                .addStatement("return this as R")
                .returns(rxHttp)
                .build())
        methodList.add(
            FunSpec.builder("isAssemblyEnabled")
                .addStatement("return param.isAssemblyEnabled()")
                .build())
        methodList.add(
            FunSpec.builder("getUrl")
                .addStatement("return param.getUrl()")
                .build())
        methodList.add(
            FunSpec.builder("getSimpleUrl")
                .addStatement("return param.getSimpleUrl()")
                .build())
        methodList.add(
            FunSpec.builder("getHeader")
                .addParameter("key", String::class)
                .addStatement("return param.getHeader(key)")
                .build())
        methodList.add(
            FunSpec.builder("getHeaders")
                .addStatement("return param.getHeaders()")
                .build())
        methodList.add(
            FunSpec.builder("getHeadersBuilder")
                .addStatement("return param.getHeadersBuilder()")
                .build())
        methodList.add(
            FunSpec.builder("buildRequest")
                .addStatement("return param.buildRequest()")
                .build())
        methodList.add(
            FunSpec.builder("tag")
                .addParameter("tag", Any::class.asTypeName().copy(nullable = true))
                .addStatement("param.tag(tag)")
                .addStatement("return this as R")
                .returns(rxHttp)
                .build())
        methodList.add(
            FunSpec.builder("tag")
                .addTypeVariable(t)
                .addParameter("type", classTName)
                .addParameter("tag", t.copy(nullable = true))
                .addStatement("param.tag(type,tag)")
                .addStatement("return this as R")
                .returns(rxHttp)
                .build())
        methodList.add(
            FunSpec.builder("cacheControl")
                .addParameter("cacheControl", cacheControlName)
                .addStatement("param.cacheControl(cacheControl)")
                .addStatement("return this as R")
                .returns(rxHttp)
                .build())
        methodList.add(
            FunSpec.builder("setCacheKey")
                .addParameter("cacheKey", String::class)
                .addStatement("param.setCacheKey(cacheKey)")
                .addStatement("return this as R")
                .returns(rxHttp)
                .build())
        methodList.add(
            FunSpec.builder("setCacheValidTime")
                .addParameter("cacheValidTime", Long::class)
                .addStatement("param.setCacheValidTime(cacheValidTime)")
                .addStatement("return this as R")
                .returns(rxHttp)
                .build())
        methodList.add(
            FunSpec.builder("setCacheMode")
                .addParameter("cacheMode", cacheModeName)
                .addStatement("param.setCacheMode(cacheMode)")
                .addStatement("return this as R")
                .returns(rxHttp)
                .build())
        return methodList
    }
}