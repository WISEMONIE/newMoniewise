package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.BlogMedia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BlogMediaRepository extends JpaRepository<BlogMedia, Long> {

    List<BlogMedia> findByBlogPostIdOrderBySortOrderAsc(Long blogPostId);
}
