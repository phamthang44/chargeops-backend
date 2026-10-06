package com.thang.chargeops.station.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** Small, rebuildable search projection. PostgreSQL still decides public visibility. */
@Service
@ConditionalOnProperty(prefix = "app.search", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class StationSearchIndex implements ApplicationRunner {
    static final String INDEX = "chargeops-stations-demo";
    static final int DEMO_LIMIT = 10_000;
    private final ElasticsearchClient client;
    private final JdbcTemplate jdbc;

    @Value("${app.search.reindex-on-startup:true}")
    private boolean reindexOnStartup;

    public record Document(String id, String name, String address, String code) {}

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (reindexOnStartup) {
            log.info("Elasticsearch demo indexed {} stations", reindex());
        }
    }

    /** Demo-only rebuild: removes stale documents, creates explicit mapping, bulk indexes DB rows. */
    public synchronized int reindex() throws IOException {
        List<Document> documents = jdbc.query("""
                SELECT id, name, address_line, station_code FROM stations
                WHERE deleted_at IS NULL ORDER BY id LIMIT 10001
                """, (rs, row) -> new Document(rs.getString("id"), rs.getString("name"),
                rs.getString("address_line"), rs.getString("station_code")));
        if (documents.size() > DEMO_LIMIT) {
            throw new IllegalStateException("Elasticsearch demo supports at most 10000 stations");
        }
        if (client.indices().exists(e -> e.index(INDEX)).value()) {
            client.indices().delete(d -> d.index(INDEX));
        }
        client.indices().create(c -> c.index(INDEX)
                .settings(s -> s.numberOfShards("1").numberOfReplicas("0"))
                .mappings(m -> m
                        .properties("id", p -> p.keyword(k -> k))
                        .properties("name", p -> p.text(t -> t.analyzer("standard")))
                        .properties("address", p -> p.text(t -> t.analyzer("standard")))
                        .properties("code", p -> p.text(t -> t.analyzer("standard")))));
        for (int start = 0; start < documents.size(); start += 500) {
            BulkRequest.Builder bulk = new BulkRequest.Builder();
            for (Document document : documents.subList(start, Math.min(start + 500, documents.size()))) {
                bulk.operations(o -> o.index(i -> i.index(INDEX).id(document.id()).document(document)));
            }
            if (client.bulk(bulk.build()).errors()) {
                throw new IOException("Station bulk indexing failed; run reindex again");
            }
        }
        client.indices().refresh(r -> r.index(INDEX));
        return documents.size();
    }

    public List<UUID> searchIds(String keyword) {
        try {
            var response = client.search(s -> s.index(INDEX).size(DEMO_LIMIT)
                    .trackTotalHits(t -> t.enabled(true))
                    .query(q -> q.multiMatch(m -> m.query(keyword.trim())
                            .fields("name^3", "address", "code^2"))), Document.class);
            if (response.hits().total() != null && response.hits().total().value() > DEMO_LIMIT) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Demo search limit exceeded");
            }
            return response.hits().hits().stream().map(hit -> UUID.fromString(hit.id())).toList();
        } catch (IOException | co.elastic.clients.elasticsearch._types.ElasticsearchException e) {
            // Never pretend a failed ES search succeeded by silently using SQL LIKE.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Elasticsearch demo unavailable; start Elasticsearch and reindex", e);
        }
    }
}
