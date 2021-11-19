package com.other;

import com.acommon.MyClassPathXmlApplicationContext;
import com.config.MyPropertySource;
import com.test.A;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

public class Test {

    public static void main(String[] args) {
        AbstractApplicationContext ac = new ClassPathXmlApplicationContext("applicationContext.xml");
        System.out.println(ac.getBean(MyPropertySource.class).getName());
        Person bean = ac.getBean(Person.class);
        System.out.println(bean);
        A bean1 = ac.getBean(A.class);
        System.out.println(bean1);
        ac.close();
    }
}
