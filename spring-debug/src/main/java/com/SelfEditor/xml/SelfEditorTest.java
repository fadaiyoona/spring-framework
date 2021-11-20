package com.SelfEditor.xml;

import org.springframework.context.support.ClassPathXmlApplicationContext;

public class SelfEditorTest {

    public static void main(String[] args) {
        ClassPathXmlApplicationContext ac = new ClassPathXmlApplicationContext("selfEditor.xml");
        Customer bean = ac.getBean(Customer.class);
        System.out.println(bean);
	}
}
