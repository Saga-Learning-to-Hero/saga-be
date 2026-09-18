package com.saga.be.repository;

import com.saga.be.entity.github.Comment;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommentRepository extends JpaRepository<Comment, UUID> {}
