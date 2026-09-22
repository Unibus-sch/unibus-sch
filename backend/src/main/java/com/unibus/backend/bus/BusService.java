package com.unibus.backend.bus;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class BusService {

    private final BusRepository busRepository;

    BusService(BusRepository busRepository) {
        this.busRepository = busRepository;
    }

    @Transactional(readOnly = true)
    List<BusResponses.ListItem> findAll() {
        return busRepository.findAll();
    }

    @Transactional(readOnly = true)
    List<BusResponses.ManagedListItem> findAllManaged() {
        return busRepository.findAllManaged();
    }

    @Transactional(readOnly = true)
    Optional<BusResponses.Detail> findById(String id) {
        return busRepository.findById(id);
    }

    @Transactional(readOnly = true)
    Optional<BusResponses.ManagedDetail> findManagedById(String id) {
        return busRepository.findManagedById(id);
    }

    @Transactional(readOnly = true)
    List<BusResponses.LatestLocation> findLatestLocations() {
        return busRepository.findLatestLocations();
    }
}
