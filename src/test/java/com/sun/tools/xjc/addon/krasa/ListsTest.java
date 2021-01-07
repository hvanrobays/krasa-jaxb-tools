package com.sun.tools.xjc.addon.krasa;

/**
 * Unfortunately there isn't a way to add JSR349 validation to a generic in JAXB 
 * (as far as I know) so I had to use a project that allows to add annotations
 * intended for the content of a collection.
 * 
 * @see https://github.com/jirutka/validator-collection
 * @author Francesco Illuminati <fillumina@gmail.com>
 */
public class ListsTest extends RunXJC2MojoTestHelper {

    @Override
    public String getFolderName() {
        return "lists";
    }

    @Override
    public String getNamespace() {
        return "a";
    }

    public void testContainer() {
        element("Container")
                .attribute("listOfString")
                        .annotation("Size")
                            .assertParam("min", 0)
                            .assertParam("max", 5).end()
                        .annotation("EachSize")
                            .assertParam("min", 1)
                            .assertParam("max", 500).end()
                        .end()
                .attribute("listOfAddress")
                        .annotation("Size")
                            .assertParam("min", 3)
                            .assertParam("max", 7).end()
                        .annotation("NotNull").assertNoValues()
                        .annotation("Valid").assertNoValues();
    }
    
    public void testAddressType() {
        element("AddressType")
                .attribute("name")
                        .annotation("NotNull").assertNoValues()
                        .end()
                .attribute("formalTitle")
                        .annotation("Size")
                            .assertParam("max", 10);
    }
}