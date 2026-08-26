package com.saga.be.repository;

import com.saga.be.entity.enums.IdentityMappingStatus;
import com.saga.be.entity.enums.IntegrationProvider;
import com.saga.be.entity.integration.IdentityMap;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdentityMapRepository extends JpaRepository<IdentityMap, UUID> {

	Optional<IdentityMap> findByProviderAndExternalAccountIdAndMappingStatusIn(
			IntegrationProvider provider, String externalAccountId, Collection<IdentityMappingStatus> statuses);

	List<IdentityMap> findByUserAccount_IdAndProvider(UUID userId, IntegrationProvider provider);

	List<IdentityMap> findByUserAccount_Id(UUID userId);
}
