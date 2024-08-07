package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.pattern.XMLPattern
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.XMLValue
import io.specmatic.core.wsdl.parser.WSDL
import io.specmatic.core.wsdl.parser.WSDLTypeInfo

class ElementInComplexType(
    private val element: XMLNode,
    val wsdl: WSDL,
    private val parentTypeName: String
): ComplexTypeChild {
    override fun process(wsdlTypeInfo: WSDLTypeInfo, existingTypes: Map<String, XMLPattern>, typeStack: Set<String>): WSDLTypeInfo {
        val wsdlElement = wsdl.getWSDLElementType(parentTypeName, element)
        val (specmaticTypeName, soapElement) = wsdlElement.getWSDLElement()

        val typeInfo = soapElement.deriveSpecmaticTypes(specmaticTypeName, existingTypes, typeStack)

        val newList: List<XMLValue> = wsdlTypeInfo.nodes.plus(typeInfo.nodes)
        val newTypes = wsdlTypeInfo.types.plus(typeInfo.types)

        return WSDLTypeInfo(newList, newTypes, typeInfo.namespacePrefixes)
    }
}