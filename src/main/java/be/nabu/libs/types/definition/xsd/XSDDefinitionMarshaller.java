package be.nabu.libs.types.definition.xsd;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import be.nabu.libs.converter.ConverterFactory;
import be.nabu.libs.converter.api.Converter;
import be.nabu.libs.property.ValueUtils;
import be.nabu.libs.property.api.Property;
import be.nabu.libs.property.api.Value;
import be.nabu.libs.types.TypeRegistryImpl;
import be.nabu.libs.types.TypeUtils;
import be.nabu.libs.types.api.Attribute;
import be.nabu.libs.types.api.ComplexType;
import be.nabu.libs.types.api.DefinedType;
import be.nabu.libs.types.api.Group;
import be.nabu.libs.types.api.MarshalException;
import be.nabu.libs.types.api.SimpleType;
import be.nabu.libs.types.api.Type;
import be.nabu.libs.types.base.Choice;
import be.nabu.libs.types.base.ComplexElementImpl;
import be.nabu.libs.types.base.Scope;
import be.nabu.libs.types.base.ValueImpl;
import be.nabu.libs.types.definition.xml.XMLDefinitionMarshaller;
import be.nabu.libs.types.properties.EnumerationProperty;
import be.nabu.libs.types.properties.LengthProperty;
import be.nabu.libs.types.properties.MaxExclusiveProperty;
import be.nabu.libs.types.properties.MaxInclusiveProperty;
import be.nabu.libs.types.properties.MaxLengthProperty;
import be.nabu.libs.types.properties.MaxOccursProperty;
import be.nabu.libs.types.properties.MinExclusiveProperty;
import be.nabu.libs.types.properties.MinInclusiveProperty;
import be.nabu.libs.types.properties.MinLengthProperty;
import be.nabu.libs.types.properties.MinOccursProperty;
import be.nabu.libs.types.properties.NameProperty;
import be.nabu.libs.types.properties.NillableProperty;
import be.nabu.libs.types.properties.PatternProperty;
import be.nabu.libs.types.properties.ScopeProperty;


/**
 * Currently there is one thing that is not supported: circular references back to the _original_ scheme
 * There are two solutions for this: also use the attachment provider to generate the main scheme (it is currently just serialized to the outputstream so there is no way to reference it with an uri)
 * Or alternatively have the main scheme _only_ the root element (no references to this allowed) and put everything else (complex types, simple types etc) in an attachment that is "included" instead of imported into the main scheme (and acts as import for the rest)
 * This file can then be referenced by attachments
 */
public class XSDDefinitionMarshaller extends XMLDefinitionMarshaller {

	public static final String NAMESPACE = "http://www.w3.org/2001/XMLSchema";
	
	private boolean hidePrivatelyScoped;
	
	private Converter converter = ConverterFactory.getInstance().getConverter();
	
	/**
	 * used to keep track of which types were already marshalled
	 */
	private TypeRegistryImpl registry;
	
	/**
	 * You can either show the entire type in one namespace
	 * Or you can reference other namespaces where necessary
	 * Now suppose you reference a complex type from another namespace,
	 * you can't just refer the user to a scheme detailing everything in that namespace as this might leak information
	 * We need attachments that are specifically built to support this xsd
	 */
	private Map<String, Document> attachments = new LinkedHashMap<String, Document>();
	
	private AttachmentProvider attachmentProvider = null;

	private boolean useExtension = false;
	
	private boolean forceAnonymousComplexTypes = false;
	
	private boolean includeSchemaLocation = true;
	
	/**
	 * The namespace of this schema
	 */
	private String namespace;
	
	/**
	 * The root schema
	 */
	private Document schema;
	
	private Property<?> [] attributeWhitelist = new Property<?> [] {
		new MinOccursProperty(),
		new MaxOccursProperty(),
		new NameProperty(),
		new NillableProperty()
	};
	
	@SuppressWarnings("rawtypes")
	private Property<?> [] restrictionWhitelist = new Property<?> [] {
		new MinLengthProperty(),
		new MaxLengthProperty(),
		new MinInclusiveProperty(),
		new MinExclusiveProperty(),
		new MaxInclusiveProperty(),
		new MaxExclusiveProperty(),
		new PatternProperty(),
		new LengthProperty(),
		new EnumerationProperty()
	};
	
	private Boolean isElementQualified = false, isAttributeQualified = false;
	
	
	public XSDDefinitionMarshaller() {
		registry = new TypeRegistryImpl();
		registry.setUseTypeIds(true);
	}
	
	/**
	 * The following definitions are retrofits to reuse the logic outside of this class
	 * Might need to refactor this a bit...
	 */
	public void define(be.nabu.libs.types.api.Element<?> element) {
		// only define named elements
		if (element.getName() != null) {
			createSchemaIfNecessary(element.getNamespace());
			define(schema.getDocumentElement(), element);
		}
	}
	public void define(SimpleType<?> simpleType) {
		if (simpleType.getName() != null) {
			createSchemaIfNecessary(getNamespace(simpleType));
			define(schema.getDocumentElement(), simpleType);
		}
	}
	public void define(ComplexType complexType) {
		if (complexType.getName() != null) {
			createSchemaIfNecessary(getNamespace(complexType));
			define(schema.getDocumentElement(), complexType);
		}
	}

	private void createSchemaIfNecessary(String namespace) {
		if (schema == null) {
			Document document = newDocument(true);
			// still need a default though
			if (isElementQualified == null) {
				isElementQualified = false;
			}
			if (isAttributeQualified == null) {
				isAttributeQualified = false;
			}
			this.namespace = namespace;
			newSchema(document, namespace, isElementQualified != null && isElementQualified, isAttributeQualified != null && isAttributeQualified);
		}
	}
	
	private void define(Node parent, be.nabu.libs.types.api.Element<?> element) {
		if (!NAMESPACE.equals(element.getNamespace())) {
			Element importedSchema = getTargetSchema(parent, element.getNamespace(), isElementQualified, isAttributeQualified);
			// only define it if it isn't defined already
			if (registry.getElement(element.getNamespace(), element.getName()) == null) {
				registry.register(element);
				writeElement(importedSchema, element);
			}
		}
	}
	
	private void define(Node parent, SimpleType<?> simpleType) {
		// unnamed, just embed
		if (simpleType.getName() == null) {
			writeSimpleType(parent, simpleType);
		}
		else {
			Element importedSchema = getTargetSchema(parent, getNamespace(simpleType), isElementQualified, isAttributeQualified);
			// only define it if it isn't defined already
			if (registry.getSimpleType(simpleType.getNamespace(), getTypeName(simpleType)) == null) {
				registry.register(simpleType);
				writeSimpleType(importedSchema, simpleType);
			}
		}
	}
	
	private void define(Node parent, ComplexType complexType) {
		if (complexType.getName() == null) {
			writeComplexType(parent, complexType);
		}
		else {
			Element importedSchema = getTargetSchema(parent, getNamespace(complexType), isElementQualified, isAttributeQualified);
			// register using the actual complex type namespace so we don't get doubles once we start playing with namespaces
			if (registry.getComplexType(complexType.getNamespace(), getTypeName(complexType)) == null) {
				registry.register(complexType);
				writeComplexType(importedSchema, complexType);
			}
		}
	}
	
	public Boolean getIsElementQualified() {
		return isElementQualified;
	}

	public void setIsElementQualified(Boolean isElementQualified) {
		this.isElementQualified = isElementQualified;
	}

	public Boolean getIsAttributeQualified() {
		return isAttributeQualified;
	}

	public void setIsAttributeQualified(Boolean isAttributeQualified) {
		this.isAttributeQualified = isAttributeQualified;
	}

	@Override
	public void marshal(OutputStream output, ComplexType type, Value<?>...values) throws IOException {
		Document document = newDocument(true);
		namespace = type.getNamespace(values);

		// the user can set this explicitly
		if (isElementQualified == null) {
			isElementQualified = type.isElementQualified(values);
		}
		if (isAttributeQualified == null) {
			isAttributeQualified = type.isAttributeQualified(values);
		}
		
		// still need a default though
		if (isElementQualified == null) {
			isElementQualified = false;
		}
		if (isAttributeQualified == null) {
			isAttributeQualified = false;
		}

		writeElement(newSchema(document, namespace, isElementQualified, isAttributeQualified), new ComplexElementImpl(type, null, values));
		
		// best to add it for xml schema
		setOmitXMLDeclaration(false);
		
		// if there is an attachment provider, we will store all the attachments
		if (attachmentProvider != null && attachments.size() > 0) {
			// store all the attachments
			for (String namespace : attachments.keySet()) {
				OutputStream attachmentOutput = attachmentProvider.getOutput(namespace);
				try {
					writeToStream(attachments.get(namespace), attachmentOutput);
				}
				finally {
					attachmentOutput.close();
				}
			}
		}
		// store the actual document
		writeToStream(document, output);
	}
	
	public AttachmentProvider getAttachmentProvider() {
		return attachmentProvider;
	}

	public void setAttachmentProvider(AttachmentProvider attachmentProvider) {
		this.attachmentProvider = attachmentProvider;
	}

	public boolean isForceAnonymousComplexTypes() {
		return forceAnonymousComplexTypes;
	}

	public void setForceAnonymousComplexTypes(boolean forceAnonymousComplexTypes) {
		this.forceAnonymousComplexTypes = forceAnonymousComplexTypes;
	}

	protected Value<?>[] fixAttributes(Value<?>...values) {
		List<Value<?>> list = new ArrayList<Value<?>>(Arrays.asList(values));
		// nabu diverges from the xml-default of nillable, this is correct when generating xsds
		boolean hasNillable = false;
		Iterator<Value<?>> iterator = list.iterator();
		while (iterator.hasNext()) {
			Value<?> value = iterator.next();
			if (NillableProperty.getInstance().equals(value.getProperty())) {
				hasNillable = true;
				// this is the xsd default, no need to explicitly set it
				if (Boolean.valueOf(false).equals(value.getValue())) {
					iterator.remove();
				}
			}
		}
		// if there is no nillable value, we assume the default ("true") is set, at which point we should set this in the xsd
		if (!hasNillable) {
			list.add(new ValueImpl<Boolean>(NillableProperty.getInstance(), true));
		}
		return list.toArray(new Value[0]);
	}
	
	private String getTypeName(Type type, Value<?>...values) {
		// xsd types
		if (NAMESPACE.equals(getNamespace(type))) {
			return type.getName(values);
		}
		// globally defined types
		else if (type instanceof DefinedType) {
			return ((DefinedType) type).getId();
		}
		else {
			// for all custom types, a "Type" suffix is added because ideally you want to follow the proper naming convention but you can't force this
			return type.getName() + "Type";
		}
	}
	
	protected void writeComplexType(Node parent, ComplexType type) {
		Document document = parent.getOwnerDocument();

		Element complexTypeElement = document.createElement("complexType");
		
		// if it's standalone, register the name
		if (parent.getNodeName().equals("schema")) {
			complexTypeElement.setAttribute("name", getTypeName(type));
		}
		
		writeAttributes(complexTypeElement, blacklist(whitelist(type.getProperties(), attributeWhitelist), NameProperty.getInstance()));
		parent.appendChild(complexTypeElement);
		parent = complexTypeElement;

		// if the complex type is in another namespace, reference that
		// we need to extend a simple type
		if (type instanceof SimpleType) {
			SimpleType<?> simpleType = (SimpleType<?>) type.get(ComplexType.SIMPLE_TYPE_VALUE).getType();
			String prefix = "";
			if (!NAMESPACE.equals(getNamespace(simpleType))) {
				define(parent, simpleType);
				prefix = getNamespacePrefix(parent, getNamespace(simpleType));
				if (prefix != null && !prefix.isEmpty()) {
					prefix += ":";
				}
			}
			Element simpleContentElement = document.createElement("simpleContent");
			Element restrictionElement = document.createElement("extension");
			restrictionElement.setAttribute("base", prefix + simpleType.getName(type.getProperties()));
			simpleContentElement.appendChild(restrictionElement);
			complexTypeElement.appendChild(simpleContentElement);
			parent = restrictionElement;
		}
		// we need to define a sequence element
		else {
			Element sequence = document.createElement("sequence");
			complexTypeElement.appendChild(sequence);
			parent = sequence;
		}
		
		// currently we just load all elements instead of referencing ids or the superType (no way to resolve them yet)
		List<be.nabu.libs.types.api.Element<?>> processedChildren = new ArrayList<be.nabu.libs.types.api.Element<?>>();
		Iterator<be.nabu.libs.types.api.Element<?>> childIterator = useExtension ? type.iterator() : TypeUtils.getAllChildrenIterator(type);
		if (useExtension && type.getSuperType() != null) {
			if (type.getSuperType() instanceof ComplexType) {
				define(parent, (ComplexType) type.getSuperType());
			}
		}
		while (childIterator.hasNext()) {
			be.nabu.libs.types.api.Element<?> child = childIterator.next();
			if (processedChildren.contains(child))
				continue;
			// do not process the value element (if any)
			if (child.getName().equals(type.get(ComplexType.SIMPLE_TYPE_VALUE)))
				continue;
			// in a lot of cases we want to hide privately scoped variables
			if (hidePrivatelyScoped) {
				Value<Scope> property = child.getProperty(ScopeProperty.getInstance());
				if (property != null && property.getValue() == Scope.PRIVATE) {
					continue;
				}
			}
			Group group = getGroup(type, child);
			if (group instanceof Choice) {
				Element childElement = document.createElement("choice");
				writeAttributes(childElement, group.getProperties());
				parent.appendChild(childElement);
				parent = childElement;
			}
			else if (group != null)
				throw new MarshalException("The xml schema marshaller only supports choice groups");
			
			writeElement(parent, child);

			// finish the choice with the other options in the group
			if (group instanceof Choice) {
				for (be.nabu.libs.types.api.Element<?> groupChild : group) {
					if (groupChild.equals(child))
						continue;
					writeElement(parent, groupChild);
					processedChildren.add(groupChild);
				}
				// reset the parent to the parent of the choice node
				parent = parent.getParentNode();
			}
		}
	}
	
	private Element newSchema(Document document, String namespace, Boolean elementQualified, Boolean attributeQualified) {
		Element schema = document.createElement("schema");
		schema.setAttribute("xmlns", NAMESPACE);
		if (elementQualified != null && elementQualified)
			schema.setAttribute("elementFormDefault", "qualified");
		if (attributeQualified != null && attributeQualified)
			schema.setAttribute("attributeFormDefault", "qualified");
		document.appendChild(schema);
		if (namespace != null) {
			schema.setAttribute("targetNamespace", namespace);
			schema.setAttribute("xmlns:tns", namespace);
		}
		// if no schema exists yet, this is the root one
		if (this.schema == null) {
			this.schema = document;
		}
		return schema;
	}
	
	private Element getTargetSchema(Node parent, String namespace, Boolean elementQualified, Boolean attributeQualified) {
		// for the root scheme, just return that
		if ((this.namespace == null && namespace == null) || (this.namespace != null && this.namespace.equals(namespace))) {
			return schema.getDocumentElement();
		}
		// get a prefix for this namespace, if it already exists, awesome, if it doesn't it is created
		getNamespacePrefix(parent, namespace);
		
		if (!attachments.containsKey(namespace)) {
			Document document = newDocument(true);
			newSchema(document, namespace, elementQualified, attributeQualified);
			attachments.put(namespace, document);
		}
		// make sure it's imported
		importSchema(parent, namespace);
		return attachments.get(namespace).getDocumentElement();
	}
	
	private void importSchema(Node parent, String namespace) {
		boolean alreadyImported = false;
		for (int i = 0; i < parent.getOwnerDocument().getDocumentElement().getChildNodes().getLength(); i++) {
			Node child = parent.getOwnerDocument().getDocumentElement().getChildNodes().item(i);
			if (child.getNodeType() == Node.ELEMENT_NODE) {
				if (child.getNodeName().equals("import")) {
					Element element = (Element) child;
					if (element.getAttribute("namespace").equals(namespace)) {
						alreadyImported = true;
						break;
					}
				}
			}
		}
		if (!alreadyImported) {
			Element importElement = parent.getOwnerDocument().createElement("import");
			importElement.setAttribute("namespace", namespace);
			String schemaLocation = null;
			if (attachmentProvider == null) {
				schemaLocation = "attachments:/" + namespace; 
			}
			else {
				 URI uri = attachmentProvider.getURI(namespace);
				 if (uri != null) {
					 schemaLocation = uri.toString();
				 }
			}
			// allow null values for schema location (e.g. in a WSDL)
			if (schemaLocation != null && includeSchemaLocation) {
				importElement.setAttribute("schemaLocation", schemaLocation);
			}
			if (parent.getOwnerDocument().getDocumentElement().getFirstChild() != null) {
				parent.getOwnerDocument().getDocumentElement().insertBefore(importElement, parent.getOwnerDocument().getDocumentElement().getFirstChild());
			}
			else {
				parent.getOwnerDocument().getDocumentElement().appendChild(importElement);
			}
		}
	}
	
	protected void writeElement(Node parent, be.nabu.libs.types.api.Element<?> child) {
		Document document = parent.getOwnerDocument();

		boolean isAttribute = child instanceof Attribute || child.getName().startsWith("@");
		Element childElement = document.createElement(isAttribute ? "attribute" : "element");
		
		if (isAttribute) {
			writeAttributes(childElement, blacklist(whitelist(child.getProperties(), attributeWhitelist), new NillableProperty(), new MinOccursProperty(), new MaxOccursProperty(), NameProperty.getInstance()));
			childElement.setAttribute("name", child.getName().startsWith("@") ? child.getName().substring(1) : child.getName());
			if (ValueUtils.getValue(new MinOccursProperty(), child.getProperties()) == 0) {
				childElement.setAttribute("use", "optional");
			}
		}
		else {
			writeAttributes(childElement, fixAttributes(whitelist(child.getProperties(), attributeWhitelist)));
		}
		
		if (!childElement.hasAttribute("name")) {
			childElement.setAttribute("name", child.getType().getName(child.getProperties()).replaceFirst("^@", ""));
		}
		
		// elements can just be added to the sequence
		if (!isAttribute) {
			parent.appendChild(childElement);
		}
		// attributes have to appear after the sequence
		else {
			parent.getParentNode().appendChild(childElement);
		}
		// if the type is named, make sure it exists on the root somewhere
		if (child.getType().getName() != null) {
			String prefix = "";
			// if its not in the xsd namespace, it is custom
			if (!NAMESPACE.equals(getNamespace(child.getType()))) {
				// define it if necessary
				if (child.getType() instanceof SimpleType) {
					define(parent, (SimpleType<?>) child.getType());
				}
				else {
					define(parent, (ComplexType) child.getType());
				}
				prefix = getNamespacePrefix(parent, getNamespace(child.getType()));
				if (prefix != null && !prefix.isEmpty()) {
					prefix += ":";
				}
			}
			
			String typeName = getTypeName(child.getType(), child.getProperties());
			childElement.setAttribute("type", prefix + typeName);
		}
		else {
			if (child.getType() instanceof SimpleType) {
				define(childElement, (SimpleType<?>) child.getType());
			}
			else {
				define(childElement, (ComplexType) child.getType());
			}
		}
	}
	
	public Map<String, Document> getAttachments() {
		return attachments;
	}
	
	@SuppressWarnings("rawtypes")
	private void writeSimpleType(Node parent, SimpleType<?> simpleType) {
		boolean standalone = parent.getNodeName().equals("schema");
		Element simpleTypeElement = parent.getOwnerDocument().createElement("simpleType");
		if (standalone) {
			simpleTypeElement.setAttribute("name", getTypeName(simpleType));
		}
		Element restrictionElement = parent.getOwnerDocument().createElement("restriction");
		// you can extend a basic type (like string) or another simple type
		// note that the restrictions are defined in the element around the type, not the type itself
		// assume default string as parent is none is given
		String simpleTypeName = simpleType.getSuperType() == null ? "string" : simpleType.getSuperType().getName();
		restrictionElement.setAttribute("base", simpleTypeName);
		Value<?> [] restrictedDefinitions = whitelist(simpleType.getProperties(), restrictionWhitelist);
		for (Value<?> restrictedDefinition : restrictedDefinitions) {
			if (restrictedDefinition.getProperty().equals(new EnumerationProperty())) {
				for (Object object : (List) restrictedDefinition.getValue()) {
					Element restriction = parent.getOwnerDocument().createElement(restrictedDefinition.getProperty().getName());
					String string = converter.convert(object, String.class);
					restriction.setAttribute("value", string);
					restrictionElement.appendChild(restriction);							
				}
			}
			else {
				Element restriction = parent.getOwnerDocument().createElement(restrictedDefinition.getProperty().getName());
				String string = converter.convert(restrictedDefinition.getValue(), String.class);
				restriction.setAttribute("value", string);
				restrictionElement.appendChild(restriction);
			}
		}
		simpleTypeElement.appendChild(restrictionElement);
		parent.appendChild(simpleTypeElement);
	}
	
	private String getNamespacePrefix(Node node, String namespace) {
		if (NAMESPACE.equals(namespace)) {
			return null;
		}
		else if (namespace == null || (this.namespace != null && this.namespace.equals(namespace))) {
			return "tns";
		}
		while (node != null && !node.getNodeName().equals("schema")) {
			node = node.getParentNode();
		}
		if (node == null) {
			throw new RuntimeException("Could not find prefix for namespace '" + namespace + "' because the schema could not be found");
		}
		NamedNodeMap attributes = ((Element) node).getAttributes();
		int highestCount = -1;
		for (int i = 0; i < attributes.getLength(); i++) {
			Attr attr = (Attr) attributes.item(i);
			if (attr.getName().startsWith("xmlns:")) {
				if (attr.getValue().equals(namespace)) {
					return attr.getName().replaceAll(".*:", "");
				}
				else {
					if (attr.getName().matches("xmlns:tns[0-9]+")) {
						int counter = new Integer(attr.getName().replaceAll("xmlns:tns([0-9]+)$", "$1"));
						if (counter > highestCount) {
							highestCount = counter;
						}
					}
				}
			}
		}
		// not yet registered
		String prefix = "tns" + ++highestCount;
		((Element) node).setAttribute("xmlns:" + prefix, namespace);
		return prefix;
	}
	
	public Document getSchema() {
		return schema;
	}
	
	public String getNamespace(Type type) {
		return type.getNamespace() == null || type.getNamespace().trim().isEmpty() ? namespace : type.getNamespace();
	}
	
	public boolean isIncludeSchemaLocation() {
		return includeSchemaLocation;
	}
	public void setIncludeSchemaLocation(boolean includeSchemaLocation) {
		this.includeSchemaLocation = includeSchemaLocation;
	}

	public boolean isHidePrivatelyScoped() {
		return hidePrivatelyScoped;
	}

	public void setHidePrivatelyScoped(boolean hidePrivatelyScoped) {
		this.hidePrivatelyScoped = hidePrivatelyScoped;
	}
	
}
