package kr.co.rhaomi.backend;

import kr.co.rhaomi.publisher.PublisherApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BackendApplication {

	public static void main(String[] args) {
		if (PublisherApplication.hasModeArgument(args)) {
			if (!PublisherApplication.isRequested(args)) {
				throw new IllegalArgumentException("Invalid publisher mode");
			}
			PublisherApplication.run(args);
			return;
		}
		SpringApplication.run(BackendApplication.class, args);
	}

}
