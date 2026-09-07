package com.saga.be.repository;

import com.saga.be.entity.enums.IdentityMappingStatus;
import com.saga.be.entity.enums.IntegrationProvider;
import com.saga.be.entity.integration.IdentityMap;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdentityMapRepository extends JpaRepository<IdentityMap, UUID> {

	Optional<IdentityMap> findByProviderAndExternalAccountIdAndMappingStatusIn(
			IntegrationProvider provider, String externalAccountId, Collection<IdentityMappingStatus> statuses);

	List<IdentityMap> findByUserAccount_IdAndProvider(UUID userId, IntegrationProvider provider);

	List<IdentityMap> findByUserAccount_Id(UUID userId);

	@Query(
			"""
			select m from IdentityMap m
			join fetch m.userAccount
			where m.provider = :provider
			  and m.externalAccountId in :externalIds
			  and m.mappingStatus in :statuses
			""")
	List<IdentityMap> findFetchedByProviderAndExternalAccountIdInAndMappingStatusIn(
			@Param("provider") IntegrationProvider provider,
			@Param("externalIds") Collection<String> externalIds,
			@Param("statuses") Collection<IdentityMappingStatus> statuses);

	@Query(
			"""
			select m from IdentityMap m
			join fetch m.userAccount
			where m.provider = :provider
			  and lower(m.externalUsername) in :usernames
			  and m.mappingStatus in :statuses
			""")
	List<IdentityMap> findFetchedByProviderAndExternalUsernameLowerInAndMappingStatusIn(
			@Param("provider") IntegrationProvider provider,
			@Param("usernames") Collection<String> usernames,
			@Param("statuses") Collection<IdentityMappingStatus> statuses);
}
