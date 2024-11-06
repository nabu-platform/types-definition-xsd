/*
* Copyright (C) 2016 Alexander Verbruggen
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Lesser General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public License
* along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

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
