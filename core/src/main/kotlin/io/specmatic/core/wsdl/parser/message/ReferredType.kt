package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.XMLPattern
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.XMLNode
import io.specmatic.core.wsdl.parser.SOAPMessageType
import io.specmatic.core.wsdl.parser.WSDL
import io.specmatic.core.wsdl.parser.WSDLTypeInfo
import io.specmatic.core.wsdl.payload.SOAPPayload

data class ReferredType(val wsdlTypeReference: String, val element: XMLNode, val wsdl: WSDL, val namespaceQualification: NamespaceQualification? = null):
    WSDLElement {
    private val elementType: WSDLElement
      get() {
          val typeNode: XMLNode = element.attributes["type"]?.let {
              wsdl.getSimpleTypeXMLNode(element)
          } ?: element

          return fromRestriction(typeNode)?.let { type ->
              if(!isPrimitiveType(typeNode))
                  throw ContractException("Simple type $type in restriction not recognized")

              SimpleElement(wsdlTypeReference, element.copy(attributes = element.attributes.plus("type" to StringValue(type))), wsdl)
          } ?: ComplexElement(wsdlTypeReference, element, wsdl)
      }

    override fun deriveSpecmaticTypes(
        specmaticTypeName: String,
        existingTypes: Map<String, XMLPattern>,
        typeStack: Set<String>
    ): WSDLTypeInfo {
        return elementType.deriveSpecmaticTypes(specmaticTypeName, existingTypes, typeStack)
    }

    override fun getSOAPPayload(
        soapMessageType: SOAPMessageType,
        nodeNameForSOAPBody: String,
        specmaticTypeName: String,
        namespaces: Map<String, String>,
        typeInfo: WSDLTypeInfo
    ): SOAPPayload {
        return elementType.getSOAPPayload(soapMessageType, nodeNameForSOAPBody, specmaticTypeName, namespaces, typeInfo)
    }
}