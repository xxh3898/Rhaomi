package kr.co.rhaomi.backend;

import kr.co.rhaomi.publisher.PublisherApplication;
import kr.co.rhaomi.production.ProductionDatabaseTaskApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BackendApplication {

	public static void main(String[] args) {
		if (ProductionDatabaseTaskApplication.hasModeArgument(args)) {
			try (var ignored = ProductionDatabaseTaskApplication.run(args)) {
				// 선택한 production one-shot task 완료 뒤 context를 종료한다.
			}
			return;
		}
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
