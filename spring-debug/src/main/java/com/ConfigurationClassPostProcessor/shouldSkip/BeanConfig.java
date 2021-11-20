package com.ConfigurationClassPostProcessor.shouldSkip;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportAware;
import org.springframework.core.type.AnnotationMetadata;

@Conditional({WindowsCondition.class})
@Configuration
public class BeanConfig implements ImportAware {
	private AnnotationMetadata importMetadata;

    @Bean(name = "bill")
    public Person person1(){
        return new Person("Bill Gates",62);
    }
    @Conditional({LinuxCondition.class})
    @Bean("linus")
    public Person person2(){
        return new Person("Linus",48);
    }

	@Override
	public void setImportMetadata(AnnotationMetadata importMetadata) {
    	this.importMetadata = importMetadata;
		System.out.println(this.importMetadata);
	}
}