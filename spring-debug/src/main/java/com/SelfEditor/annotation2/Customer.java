package com.SelfEditor.annotation2;

import org.springframework.beans.factory.annotation.Value;

/**
 * @author dyan
 * @data 2020/12/12
 */
public class Customer {

    @Value("${customer.name}")
    private String name;

    @Value("${customer.address}")
    private Address address;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Address getAddress() {
        return address;
    }

    public void setAddress(Address address) {
        this.address = address;
    }

    @Override
    public String toString() {
        return "Customer{" +
                "name='" + name + '\'' +
                ", address=" + address +
                '}';
    }
}
