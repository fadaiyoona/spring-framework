package com.factoryBean;

import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

public class TestFactoryBean {

    public static void main(String[] args) {
        ApplicationContext ac = new ClassPathXmlApplicationContext("factoryBean.xml");
        MyFactoryBean bean1 = (MyFactoryBean) ac.getBean( "&myFactoryBean");
        System.out.println(bean1);
        User bean = (User) ac.getBean("myFactoryBean");
        System.out.println(bean.getName());
        User bean2 = (User) ac.getBean("myFactoryBean");
        System.out.println(bean2.getName());
    }
}
