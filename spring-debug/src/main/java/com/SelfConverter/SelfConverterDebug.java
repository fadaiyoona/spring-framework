package com.SelfConverter;

import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.core.convert.ConversionService;

public class SelfConverterDebug {

    public static void main(String[] args) {
        ClassPathXmlApplicationContext ac = new ClassPathXmlApplicationContext("selfConverter.xml");
        ConversionService bean = ac.getBean(ConversionService.class);
        Student convert = bean.convert("1_zhangsan", Student.class);
        System.out.println(convert);
	}
}
