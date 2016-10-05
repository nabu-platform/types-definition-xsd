package be.nabu.libs.types.definition.xsd;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import be.nabu.libs.types.java.BeanType;

public class TestXSD {
	public static void main(String...args) throws IOException {
		XSDDefinitionMarshaller marshaller = new XSDDefinitionMarshaller();
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		marshaller.marshal(output, new BeanType<Company>(Company.class));
		System.out.println(new String(output.toByteArray(), "UTF-8"));
	}
	
}
