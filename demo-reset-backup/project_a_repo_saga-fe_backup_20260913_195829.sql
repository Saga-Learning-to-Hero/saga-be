-- MySQL dump 10.13  Distrib 8.0.40, for Win64 (x86_64)
--
-- Host: saga-mysql-dev-saga-learning-to-hero-dev.g.aivencloud.com    Database: saga_dev
-- ------------------------------------------------------
-- Server version	8.4.8

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
SET @MYSQLDUMP_TEMP_LOG_BIN = @@SESSION.SQL_LOG_BIN;
SET @@SESSION.SQL_LOG_BIN= 0;

--
-- GTID state at the beginning of the backup 
--

SET @@GLOBAL.GTID_PURGED=/*!80000 '+'*/ '1919aa18-a7bb-11f1-81bc-d6392ca842b3:1-914,
2b117fa4-a50b-11f1-94dc-4693fd8fc2f3:1-19,
40a37216-a6ab-11f1-8242-c68388d978d4:1-15,
706e5ee9-9f98-11f1-9cf2-ce9be0ce648e:1-92,
92570e28-a103-11f1-9392-0e34d3ade6a9:1-38,
b460b840-a38a-11f1-b5cf-5617d7f8921d:1-71,
ce0386b2-9ea7-11f1-ad68-9266917ecf9e:1-38';

--
-- Table structure for table `git_repo`
--

DROP TABLE IF EXISTS `git_repo`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `git_repo` (
  `id` char(36) NOT NULL,
  `project_id` char(36) NOT NULL,
  `installation_id` char(36) DEFAULT NULL,
  `name` varchar(255) DEFAULT NULL,
  `url` varchar(500) DEFAULT NULL,
  `provider` varchar(32) NOT NULL DEFAULT 'GITHUB',
  `repository_id` bigint DEFAULT NULL,
  `owner_login` varchar(255) DEFAULT NULL,
  `full_name` varchar(255) DEFAULT NULL,
  `default_branch` varchar(128) DEFAULT NULL,
  `repository_role` varchar(32) DEFAULT NULL,
  `is_private` tinyint(1) DEFAULT NULL,
  `connection_status` varchar(32) NOT NULL,
  `sync_cursor` datetime(6) DEFAULT NULL,
  `consecutive_failures` int NOT NULL DEFAULT '0',
  `last_synced_at` datetime(6) DEFAULT NULL,
  `review_cutover_at` datetime(6) DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `active_provider` varchar(32) GENERATED ALWAYS AS ((case when (`connection_status` = _utf8mb4'ACTIVE') then `provider` else NULL end)) STORED,
  `active_repository_id` bigint GENERATED ALWAYS AS ((case when (`connection_status` = _utf8mb4'ACTIVE') then `repository_id` else NULL end)) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_git_repo_project_full_name` (`project_id`,`full_name`),
  UNIQUE KEY `uk_git_repo_active_provider_repository` (`active_provider`,`active_repository_id`),
  UNIQUE KEY `uk_git_repo_project_provider_repository` (`project_id`,`provider`,`repository_id`),
  KEY `fk_git_repo_installation` (`installation_id`),
  KEY `ix_git_repo_project_status` (`project_id`,`connection_status`),
  CONSTRAINT `fk_git_repo_installation` FOREIGN KEY (`installation_id`) REFERENCES `github_installation` (`id`),
  CONSTRAINT `fk_git_repo_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `git_repo`
--
-- WHERE:  id='083b44b6-1db8-47f7-8321-00b51054b0fa'

/*!40000 ALTER TABLE `git_repo` DISABLE KEYS */;
INSERT INTO `git_repo` (`id`, `project_id`, `installation_id`, `name`, `url`, `provider`, `repository_id`, `owner_login`, `full_name`, `default_branch`, `repository_role`, `is_private`, `connection_status`, `sync_cursor`, `consecutive_failures`, `last_synced_at`, `review_cutover_at`, `version`, `created_at`, `updated_at`) VALUES ('083b44b6-1db8-47f7-8321-00b51054b0fa','f05f57e1-c885-4c32-9a03-50062c6dff10','5af31d19-e7ab-414e-b700-f2d73f2c730d','saga-fe',NULL,'GITHUB',1338790015,'Saga-Learning-to-Hero','Saga-Learning-to-Hero/saga-fe','main','FRONTEND',0,'REVOKED',NULL,0,'2026-09-08 04:45:26.278923',NULL,3,'2026-09-03 21:33:51.309706','2026-09-09 05:06:24.136786');
/*!40000 ALTER TABLE `git_repo` ENABLE KEYS */;
SET @@SESSION.SQL_LOG_BIN = @MYSQLDUMP_TEMP_LOG_BIN;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-09-13 19:58:32
-- MySQL dump 10.13  Distrib 8.0.40, for Win64 (x86_64)
--
-- Host: saga-mysql-dev-saga-learning-to-hero-dev.g.aivencloud.com    Database: saga_dev
-- ------------------------------------------------------
-- Server version	8.4.8

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
SET @MYSQLDUMP_TEMP_LOG_BIN = @@SESSION.SQL_LOG_BIN;
SET @@SESSION.SQL_LOG_BIN= 0;

--
-- GTID state at the beginning of the backup 
--

SET @@GLOBAL.GTID_PURGED=/*!80000 '+'*/ '1919aa18-a7bb-11f1-81bc-d6392ca842b3:1-914,
2b117fa4-a50b-11f1-94dc-4693fd8fc2f3:1-19,
40a37216-a6ab-11f1-8242-c68388d978d4:1-15,
706e5ee9-9f98-11f1-9cf2-ce9be0ce648e:1-92,
92570e28-a103-11f1-9392-0e34d3ade6a9:1-38,
b460b840-a38a-11f1-b5cf-5617d7f8921d:1-71,
ce0386b2-9ea7-11f1-ad68-9266917ecf9e:1-38';

--
-- Table structure for table `git_commit`
--

DROP TABLE IF EXISTS `git_commit`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `git_commit` (
  `id` char(36) NOT NULL,
  `repo_id` char(36) NOT NULL,
  `author_student_id` char(36) DEFAULT NULL,
  `sha_hash` varchar(64) NOT NULL,
  `github_commit_id` varchar(64) DEFAULT NULL,
  `author_external_id` varchar(128) DEFAULT NULL,
  `message` mediumtext,
  `committed_at` datetime(6) DEFAULT NULL,
  `additions` int DEFAULT NULL,
  `deletions` int DEFAULT NULL,
  `files_changed` int DEFAULT NULL,
  `signature_verified` tinyint(1) DEFAULT NULL,
  `verification_reason` varchar(64) DEFAULT NULL,
  `head_ref` varchar(255) DEFAULT NULL,
  `external_updated_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_git_commit_repo_sha` (`repo_id`,`sha_hash`),
  KEY `ix_git_commit_sha` (`sha_hash`),
  KEY `fk_git_commit_author` (`author_student_id`),
  CONSTRAINT `fk_git_commit_author` FOREIGN KEY (`author_student_id`) REFERENCES `student_profile` (`id`),
  CONSTRAINT `fk_git_commit_repo` FOREIGN KEY (`repo_id`) REFERENCES `git_repo` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `git_commit`
--
-- WHERE:  repo_id='083b44b6-1db8-47f7-8321-00b51054b0fa'

/*!40000 ALTER TABLE `git_commit` DISABLE KEYS */;
INSERT INTO `git_commit` (`id`, `repo_id`, `author_student_id`, `sha_hash`, `github_commit_id`, `author_external_id`, `message`, `committed_at`, `additions`, `deletions`, `files_changed`, `signature_verified`, `verification_reason`, `head_ref`, `external_updated_at`, `created_at`, `updated_at`) VALUES ('51d0b757-effb-49fd-968a-f6fe4aff4e47','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'04d2cadbb510313daef63d51a525d46b7cb01038','04d2cadbb510313daef63d51a525d46b7cb01038','212834962','feat: [FE][SAGA-4] App Shell Layout voi Multi-Role Navigation\n\n- Tao Sidebar component (src/components/layout/sidebar.tsx):\n  + NAV_ITEMS voi roles filter: Dashboard (all), Graph/Tasks/Assessment (LECTURER+STUDENT), Integrations/Settings (ADMIN)\n  + Active state highlight dung usePathname()\n  + Collapsible toggle voi Tooltip hint khi collapsed\n  + Responsive sidebar cho desktop\n- Tao Header component (src/components/layout/header.tsx):\n  + Semester Switcher dropdown (Spring/Fall 2026)\n  + Dark/Light Mode Toggle voi localStorage persist\n  + User Menu: Avatar, Name, Role Badge, Role Switcher (mock), Logout button\n- Tao Dashboard Layout (src/app/(dashboard)/layout.tsx):\n  + Auth Guard: redirect /login neu chua authenticated\n  + Responsive: desktop sidebar + mobile Sheet drawer\n  + Main content area flex-1 overflow-y-auto p-6\n- Tao placeholder Dashboard page\n- Cap nhat root layout: them TooltipProvider, boc QueryProvider\n- Cai shadcn: dropdown-menu, avatar, select, tooltip, sheet\n- Fix TypeScript: @base-ui Tooltip/Menu API khac radix (bo asChild, delayDuration)','2026-08-25 07:37:57.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:23.648017','2026-09-08 04:23:47.460720'),('18850cbb-0c41-4735-b321-8889ffd99121','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'08bd3d08d0a987eb8757b22af24372c383042d1b','08bd3d08d0a987eb8757b22af24372c383042d1b','buiminh20','test:[FE][SAGA-52] unit test cho các api đã tích hợp','2026-09-08 15:53:42.000000',NULL,NULL,NULL,NULL,NULL,'feat/SAGA-43-admin-academic-course-roster',NULL,'2026-09-08 08:54:03.707771','2026-09-09 04:59:24.275522'),('48d778f0-bf5d-45e9-ba3c-72302b77386c','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'0a0d5fbc4fba18bad1080631ab5bd47cb1c73b84','0a0d5fbc4fba18bad1080631ab5bd47cb1c73b84','212834962','feat: [FE][SAGA-34] Xay dung bo Rules va Workflows dong bo UI UX The khoa hoc va Performance toan he thong','2026-08-30 07:53:08.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:36.047756','2026-09-08 04:23:47.157127'),('131ca2a8-40f3-4138-ad8d-e46338496578','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'0c53efe5ca2a25273c9eafd3daaa62a3607943bf','0c53efe5ca2a25273c9eafd3daaa62a3607943bf','212834962','Merge pull request #20 from Saga-Learning-to-Hero/feat/SAGA-41-standardize-se-dev-terms\n\nfeat: [FE][SAGA-41] Chuan hoa thuat ngu SE Dev va nang cap da tich ho…','2026-09-04 02:21:11.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:40.178887','2026-09-08 04:23:45.644293'),('aaf1eb76-9e8d-45a4-a5f3-1112c498346f','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'0e029e4a8a04fa1571ed6fde0e45d92f8b638548','0e029e4a8a04fa1571ed6fde0e45d92f8b638548','169630551','Merge remote-tracking branch \'origin/dev\' into feat/SAGA-17-lecturer-final-grades\n\n# Conflicts:\n#	src/app/(dashboard)/layout.tsx','2026-08-29 15:12:34.000000',NULL,NULL,NULL,NULL,NULL,'feat/SAGA-17-lecturer-final-grades',NULL,'2026-09-08 04:23:57.013544','2026-09-08 04:23:57.013572'),('f88ace40-447a-466b-9892-31093bca2a1c','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'102e018b05ad9b0b7a1904f41d559279216b5187','102e018b05ad9b0b7a1904f41d559279216b5187','212834962','feat: [FE][SAGA-7] Xay dung UI/UX Quan ly Du lieu hoc thuat (Khoa hoc, Lop hanh chinh, Mon hoc, Hoc ky)','2026-08-26 03:46:08.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:24.939734','2026-09-08 04:23:52.399599'),('f676dcc1-2e60-45c2-9814-0c78f28f749b','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'10be6a223ab052e2da801f2c8bd09d14d347de14','10be6a223ab052e2da801f2c8bd09d14d347de14','212834962','feat: [FE][SAGA-40] Tai thiet ke do thi truy xuat va mang luoi SNA cho Sinh vien va Giang vien','2026-09-03 16:18:05.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:39.403581','2026-09-08 04:23:52.248665'),('48b1f9d0-6961-4add-8eaa-35c210ddab68','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'10f69ec81417ff2bd51c47fadad99fa60c273835','10f69ec81417ff2bd51c47fadad99fa60c273835','169630551','feat: [FE][SAGA-15] finalize role-based lecturer class selection','2026-08-27 14:11:12.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:27.781004','2026-09-08 04:23:47.081524'),('e99d7241-3107-4439-ae1e-672d25ee510b','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'119cf6f1b5ba013039c27d8237f185379a4496a0','119cf6f1b5ba013039c27d8237f185379a4496a0','169630551','chore: [FE][SAGA-37] Chuẩn hóa quy tắc danh tính Git cho thành viên','2026-09-03 14:28:53.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:38.370143','2026-09-08 04:23:51.944702'),('ecb23462-16bf-4547-80fa-71921406090b','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'12442d76b2e6b451e2bd67a66a9ad9119f69c5b6','12442d76b2e6b451e2bd67a66a9ad9119f69c5b6','212834962','fix(routing): optimize nav config, not-found redirect and fix sprint modal lint errors','2026-08-28 03:37:40.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:29.589483','2026-09-08 04:23:52.020072'),('2fb7a1cc-a79a-46c8-858a-11c53f56bb16','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'166383f2b703caaf54c819ccf5be8b39eefa783e','166383f2b703caaf54c819ccf5be8b39eefa783e','212834962','feat: [FE][SAGA-8] Xay dung UI/UX Nhat ky kiem toan he thong va Trang 404 Not Found','2026-08-26 03:54:37.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:25.198028','2026-09-08 04:23:46.173822'),('ad0c9dfd-8fc9-451a-9ef7-d65a1027cafd','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'195cc288acecd055e6cbb481e30b8128af3aa081','195cc288acecd055e6cbb481e30b8128af3aa081','147216351','feat: [FE][SAGA-33] Hoan thien UI/UX đánh giá chéo cho student','2026-08-29 13:55:07.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:33.981395','2026-09-08 04:23:49.500847'),('b1b2df1b-496a-493d-b239-51f4f5a6c890','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'19b441bd5f50d1634b73f3e72a6bb7d0670602de','19b441bd5f50d1634b73f3e72a6bb7d0670602de','212834962','feat: [FE][SAGA-36] Chuan hoa luong xac thuc Auth, tich hop Sonner Toast va toi uu route guard','2026-09-06 12:59:36.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:41.469992','2026-09-08 04:23:49.978342'),('b02d40e7-0e78-4bbd-bf18-eb3729ee837c','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'1aaa83ae9914bc3977cf3439fcc213253e841311','1aaa83ae9914bc3977cf3439fcc213253e841311','147216351','feat: [FE][SAGA-50] Tích hợp API Danh mục loại dự án và tạo dự án','2026-09-07 07:40:15.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:44.308020','2026-09-08 04:23:49.727457'),('6d1c0737-f827-42f4-a9a4-36683dd6d1cd','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'1c839474d0f0e786f026737dd70be959085b0142','1c839474d0f0e786f026737dd70be959085b0142','169630551','feat: [SAGA-18][SAGA-20][SAGA-21] hoàn thiện theo dõi và phân tích dự án nhóm','2026-08-29 19:14:37.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:35.014669','2026-09-08 04:23:48.065416'),('e46f6f0d-f7a0-4359-b937-3a334b160656','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'21394e3741f9f2048dbe6882953531f1c2f33ccc','21394e3741f9f2048dbe6882953531f1c2f33ccc','147216351','feat: [FE][SAGA-28] Xay dung UI/UX quan li commit github','2026-08-28 16:14:54.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:29.847528','2026-09-08 04:23:51.640536'),('d3408d51-aa39-4620-87af-d333e62fa38d','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'21ad806e30d5a310a429bf09adeddf33be50e624','21ad806e30d5a310a429bf09adeddf33be50e624','212834962','feat: standardize 3-role fields, course navigation, and global profile modal','2026-08-30 02:02:20.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:35.789547','2026-09-08 04:23:50.959827'),('c5250c34-88de-4bdd-95a1-9865ff5197a3','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'259c971a2d593ac3a8b4556fc3a6e624e02d48d6','259c971a2d593ac3a8b4556fc3a6e624e02d48d6','212834962','Merge pull request #10 from Saga-Learning-to-Hero/feat/SAGA-17-lecturer-final-grades\n\nFeat/saga 17 lecturer final grades','2026-08-29 11:15:51.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:33.463993','2026-09-08 04:23:50.129013'),('ab478857-28e6-40f4-8309-6fac91e2f540','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'2625bbee0259cc4a144740fe10d2df4c04b837f0','2625bbee0259cc4a144740fe10d2df4c04b837f0','147216351','feat: [FE][SAGA-23] Xay dung UI/UX tong quan tien do và ket qua nhom','2026-08-26 16:41:53.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:26.231194','2026-09-08 04:23:49.350025'),('bbe045b3-b552-4946-9ada-25a16eb46c13','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'29bac5bd68c6c628463eb27ec5b3ec3279bf18fb','29bac5bd68c6c628463eb27ec5b3ec3279bf18fb','169630551','fix: giữ phiên đăng nhập và khôi phục menu tài khoản','2026-08-29 15:08:56.000000',NULL,NULL,NULL,NULL,NULL,'feat/SAGA-17-lecturer-final-grades',NULL,'2026-09-08 04:23:57.089677','2026-09-08 04:23:57.089708'),('d6167df9-572b-4136-bc31-f4093ffc05be','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'29cb37eb9875cde4fb2ff7c02b08721cfc72e26b','29cb37eb9875cde4fb2ff7c02b08721cfc72e26b','212834962','feat: [FE][SAGA-6] Xay dung UI/UX Quan ly Du an va Trang chi tiet Nhom do an (Admin)\n\n- Tao feature module src/features/admin/projects/ (types, data, components)\n- Su dung 100% shadcn/ui (Card, Table, Badge, Button, Avatar, Input)\n- Tinh gon bang danh sach tai /admin/projects: hien thi nhom, ma de tai, hoc ky, GVHD, tong quan tich hop Webhook va trang thai\n- Xay dung trang chi tiet chuyen sau tai /admin/projects/[id]:\n  + Banner thong tin do an va hoc phan\n  + The Giang vien huong dan va Truong nhom (Leader)\n  + The tich hop Jira Software (Project Key, tong tasks, last sync)\n  + The tich hop GitHub Repository (Repo URL, tong commits, last sync)\n  + Bang thanh vien nhom do an kem ty le phan bo dong gop (%) va so commit/task chi tiet\n- Sua loi hover text button \'Xem trang\'\n- TypeScript va Next.js build pass 0 error','2026-08-26 03:14:13.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:24.681526','2026-09-08 04:23:51.185961'),('3bb5d7fd-1adc-40bb-873a-9f161c182c67','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'35bf77dccf32bbcd8ceae5e9cf43454e991e7279','35bf77dccf32bbcd8ceae5e9cf43454e991e7279','212834962','feat: [FE][SAGA-46] Xay dung UI Form tao moi, cap nhat va cac Dialog quan tri Hoc thuat, Mon hoc','2026-09-07 04:36:52.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:43.534153','2026-09-08 04:23:46.628062'),('fdb8f484-9a9a-448c-9b8b-3e9b683c363e','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'387026cecdc835dcb3c6d4344736027f2e562f01','387026cecdc835dcb3c6d4344736027f2e562f01','169630551','chore: đồng bộ nhánh SAGA-15 với dev mới nhất','2026-08-29 09:10:44.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:32.689610','2026-09-08 04:23:52.550129'),('9df97442-3fa1-4a36-a526-f66368a1f5f9','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'3e8ce9611f3e023bb661cb53254c293305c2b217','3e8ce9611f3e023bb661cb53254c293305c2b217','147216351','feat: [FE][SAGA-24] Xay dung UI/UX Tao va cau hinh JIra-Github cho dự án','2026-08-27 08:47:22.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:27.006277','2026-09-08 04:23:49.048672'),('55d7d490-4cd8-4c4e-aac6-c1805539f30e','083b44b6-1db8-47f7-8321-00b51054b0fa','80ffd344-5190-4373-a2fb-10e74d64e55d','3fbc5433cc1f807ec4b5e526aad590a557e7a028','3fbc5433cc1f807ec4b5e526aad590a557e7a028','Zedhuynh0210','feat: [FE][SAGA-51] Sửa UI student cho hoàn chỉnh','2026-09-08 21:01:41.000000',NULL,NULL,NULL,NULL,NULL,'feat/SAGA-43-admin-academic-course-roster',NULL,'2026-09-08 14:01:46.711773','2026-09-09 04:59:24.520091'),('fd531abe-0e40-4fd1-8105-76d037bfff8d','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'416155c6ba4a8570c38dca0efc5542823298722f','416155c6ba4a8570c38dca0efc5542823298722f','212834962','setup: [FE][SAGA-1] Cập nhật file agent-rule cho FE','2026-08-24 02:43:21.000000',NULL,NULL,NULL,NULL,NULL,'chore/SAGA-1-setup-core-libraries',NULL,'2026-09-08 02:16:22.354932','2026-09-08 04:23:42.695324'),('7fc52623-326b-45b3-a5b3-2e487170da9f','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'425e215b88bf4c49ab0cfefee7a7e6b7bdff418e','425e215b88bf4c49ab0cfefee7a7e6b7bdff418e','212834962','Merge pull request #6 from Saga-Learning-to-Hero/fix/SAGA-29-fix-team-issues\n\nfix: [FE][SAGA-29] Fix toan bo ESLint warnings va dong bo CustomSelec…','2026-08-29 05:08:44.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:30.621841','2026-09-08 04:23:48.595084'),('869304cf-d3b6-423c-ab17-9f14ca891c58','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'43f4052c8ff027cd25c22c3091519f3a9a9908ce','43f4052c8ff027cd25c22c3091519f3a9a9908ce','212834962','feat: [FE][SAGA-42] Xay dung bo Unit Test toan dien cho phan he Xac thuc Auth Service','2026-09-06 13:16:22.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:41.986003','2026-09-08 04:23:48.822394'),('cba8e841-dd94-4ce1-baef-ad7ddf11ccf2','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'455ecbe8250862baefb34d1a4bf7dc3aef8a6ba6','455ecbe8250862baefb34d1a4bf7dc3aef8a6ba6','212834962','feat: [FE][SAGA-40] Toi uu bo cuc graph-first dua do thi lam trung tam va thu gon bo loc','2026-09-04 09:54:22.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:40.953779','2026-09-08 04:23:50.581589'),('e7dffb71-6a5d-4549-85b5-c89f35c84447','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'45f8e72b873d9c61ab4e712070bdf6fd13fdfc89','45f8e72b873d9c61ab4e712070bdf6fd13fdfc89','212834962','feat: [SAGA-38] cập nhật giao diện admin và chuẩn hóa cấu trúc đề cương FLM FPT','2026-09-02 13:41:52.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:37.079996','2026-09-08 04:23:51.868765'),('14b0c07b-e001-4e45-862d-105b359f4682','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'4c512103b8ef714fa25fb67e2768a8c1d950ae07','4c512103b8ef714fa25fb67e2768a8c1d950ae07','169630551','feat: hoàn thiện bảng điểm tổng kết cho giảng viên','2026-08-29 10:13:26.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:33.205926','2026-09-08 04:23:45.719930'),('db6817ae-9724-400e-aee7-b10c8c50145c','083b44b6-1db8-47f7-8321-00b51054b0fa','551aa464-883a-44d3-8b19-6890d4c31f8b','4ef85d18aba69fa963eafa8ec9d1a8d0f4d33bed','4ef85d18aba69fa963eafa8ec9d1a8d0f4d33bed','lehai170504','feat: [FE][SAGA-53] Tich hop API Admin Lecturers va dong bo CustomSelect chon Giang vien khi tao Khoa hoc','2026-09-08 08:54:27.000000',NULL,NULL,NULL,NULL,NULL,'feat/SAGA-43-admin-academic-course-roster',NULL,'2026-09-08 02:15:47.035368','2026-09-09 04:59:24.764404'),('96b630ba-0cbe-4374-9409-6dc591c18feb','083b44b6-1db8-47f7-8321-00b51054b0fa','91a0ff97-e7f3-4e9e-b4c8-5182e5ceeb31','537a360af5e8598780e37ecb1419b7aafe8cd51b','537a360af5e8598780e37ecb1419b7aafe8cd51b','139128461','chore: complete initial setup with feature structure and docs','2026-08-18 19:57:40.000000',NULL,NULL,NULL,NULL,NULL,'main',NULL,'2026-09-08 04:23:41.467456','2026-09-08 04:23:41.467482'),('a6ae5f5f-3f2f-4182-bbd3-38cf09bdfaf7','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'56805e254b1e1662bcfa382d885968bf5ee0df2c','56805e254b1e1662bcfa382d885968bf5ee0df2c','212834962','feat: [FE][SAGA-47] Tich hop API va ket noi UI Quan ly Mon hoc va De cuong chi tiet Syllabus FLM','2026-09-07 04:37:10.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:43.792032','2026-09-08 04:23:49.274720'),('c805897a-aab1-4ca6-bbf6-8803b290654a','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'581cc15522caaed012a40e3fab58916efb3e21a7','581cc15522caaed012a40e3fab58916efb3e21a7','169630551','feat: [FE][SAGA-37] Hoàn thiện giao diện dashboard và màu biểu đồ','2026-09-04 08:15:40.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:40.437127','2026-09-08 04:23:50.279832'),('d19c31a0-71ea-472e-ae16-1719444c517c','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'63458cb4ea880fd206dcd0f7c3876a68e4b54404','63458cb4ea880fd206dcd0f7c3876a68e4b54404','212834962','feat: [FE][SAGA-43] Toi uu kien truc 2-Tab chi tiet mon hoc, dong bo hien thi field mon hoc va prefetch du lieu hoc thuat','2026-09-07 10:15:15.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:45.341070','2026-09-08 04:23:50.884383'),('e012b2dd-0ecc-40fc-b48e-91218e68d1e1','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'636879ebb293907a30e5b1c98db50e0dfa7c3ec5','636879ebb293907a30e5b1c98db50e0dfa7c3ec5','147216351','feat: [FE][SAGA-33] Xay dung UI/UX đánh giá chéo cho student','2026-08-29 08:28:38.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:32.430752','2026-09-08 04:23:51.565065'),('498b638b-a51b-47eb-8cd2-cf2ae7438c46','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'64c3dab95dfd355b832c92167d0d6ad782cf29d6','64c3dab95dfd355b832c92167d0d6ad782cf29d6','147216351','feat: [FE][SAGA-22] Xay dung UI/UX du lieu khoa hoc cho sinh vien','2026-08-26 14:28:43.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:25.972752','2026-09-08 04:23:47.232733'),('c9613a3a-790b-4664-afc3-a71552329f30','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'650bb605acfbec62faf275343bc91aabc20e502a','650bb605acfbec62faf275343bc91aabc20e502a','212834962','Merge pull request #7 from Saga-Learning-to-Hero/refactor/SAGA-30-role-based-routing\n\nrefactor: [FE][SAGA-30] Tai cau truc he thong dinh tuyen phan quyen c…','2026-08-29 05:42:50.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:31.654867','2026-09-08 04:23:50.355284'),('5d77175c-f673-4802-be84-8f46d4f027d3','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'6ad8ddc4976459f7facf93f08894467595def04b','6ad8ddc4976459f7facf93f08894467595def04b','147216351','feat: [FE][SAGA-27] Xay dung UI/UX quan li sprint và task jira','2026-08-27 16:28:28.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:28.813717','2026-09-08 04:23:47.687581'),('722b4eba-ac21-4678-be36-a41b59a30ed7','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'6daa22559d34a9bc3d7f7bff3aa48c710001206a','6daa22559d34a9bc3d7f7bff3aa48c710001206a','212834962','feat: [FE][SAGA-41] Chuan hoa thuat ngu SE Dev va nang cap da tich hop 1 cham qua link Jira va GitHub','2026-09-04 02:19:56.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:39.920821','2026-09-08 04:23:48.141558'),('460b824d-edf3-4701-b716-00189ae8c4ee','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'70063182186d7908115fba38c8a0d8021aea006e','70063182186d7908115fba38c8a0d8021aea006e','212834962','feat: [FE][SAGA-9] Xay dung UI/UX Dashboard tong quan cho Quan tri vien (Admin Dashboard Overview)','2026-08-26 04:04:29.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:25.456320','2026-09-08 04:23:46.930147'),('40c03b1c-18e5-4d36-bd17-4f91625443fa','083b44b6-1db8-47f7-8321-00b51054b0fa','551aa464-883a-44d3-8b19-6890d4c31f8b','701a49d1672ebcdcfd2d22cc2bce0b7fab49f497','701a49d1672ebcdcfd2d22cc2bce0b7fab49f497','lehai170504','feat: [FE][SAGA-43] Chuan hoa thuat ngu hoc thuat SE FPT, bo sung chon de cuong va sua loi roster preview contract','2026-09-07 21:54:48.000000',NULL,NULL,NULL,NULL,NULL,'feat/SAGA-51-Integrating-Jira-GitHub-Workspace',NULL,'2026-09-08 02:16:45.598834','2026-09-08 13:13:43.171592'),('18207264-34df-4f97-b0c5-10ea776dafab','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'7135994bb86d6fd1fb2d2665747e20103a3dcdcd','7135994bb86d6fd1fb2d2665747e20103a3dcdcd','212834962','Merge pull request #11 from Saga-Learning-to-Hero/feat/SAGA-33-student-peer-review\n\nfeat: [FE][SAGA-33] Hoan thien UI/UX đánh giá chéo cho student','2026-08-29 14:24:17.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:34.239451','2026-09-08 04:23:45.871377'),('b83bd41d-adc1-4359-8f8f-4eb17469591f','083b44b6-1db8-47f7-8321-00b51054b0fa','91a0ff97-e7f3-4e9e-b4c8-5182e5ceeb31','746c95a1734ce1973be42cbf01c3029d355d962d','746c95a1734ce1973be42cbf01c3029d355d962d','139128461','Initial commit from Create Next App','2026-08-18 19:42:34.000000',NULL,NULL,NULL,NULL,NULL,'main',NULL,'2026-09-08 04:23:41.543598','2026-09-08 04:23:41.543628'),('f828ceea-117d-493a-bfb7-3f8e6924b3f3','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'75f785d8588c66ade714085ed266be7ea5569d9b','75f785d8588c66ade714085ed266be7ea5569d9b','147216351','feat: [FE][SAGA-51] Format utils ngày và giờ','2026-09-07 09:08:33.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:44.825092','2026-09-08 04:23:52.324086'),('cdf22adc-3778-4b1d-826b-51665e6134fb','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'7833587a0097e3e119d69df9cbd5df87733454f4','7833587a0097e3e119d69df9cbd5df87733454f4','212834962','Merge pull request #22 from Saga-Learning-to-Hero/feat/SAGA-36-auth-integration-and-ui\n\nfeat: [FE][SAGA-36] Chuan hoa luong xac thuc Auth, tich hop Sonner To…','2026-09-06 13:00:29.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:41.728000','2026-09-08 04:23:50.732938'),('85c28b71-64d0-4830-a314-59235985a217','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'7c68b7279b00a5dd13a29d6e3024118f401c6484','7c68b7279b00a5dd13a29d6e3024118f401c6484','212834962','feat(layout): add TopHeader with user profile dropdown, theme toggle, and logout','2026-08-29 11:38:56.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:33.722899','2026-09-08 04:23:48.746801'),('5f7396ea-8917-44b8-aa95-3450c062b952','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'7d1429b6131e7ebb57796d10ba645be1ae5ee2e6','7d1429b6131e7ebb57796d10ba645be1ae5ee2e6','212834962','Merge pull request #17 from Saga-Learning-to-Hero/feat/SAGA-38-update-admin-ui-ux\n\nfeat: [SAGA-38] cập nhật giao diện admin và chuẩn hóa cấu trúc đề cươ…','2026-09-02 13:42:19.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:37.337912','2026-09-08 04:23:47.838516'),('170914c1-7495-4a36-b25b-dd9b02e12de6','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'7f3ffcd81d9347ba8547a1f46bbeebbb17b49dc3','7f3ffcd81d9347ba8547a1f46bbeebbb17b49dc3','212834962','feat: [FE][SAGA-25] Thiet ke Brand Logo S-Graph Nexus va Dong bo Header, Footer toan he thong','2026-08-27 02:39:15.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:26.489818','2026-09-08 04:23:45.795718'),('e7b5f8f3-6375-4808-bd2f-0072f832ad21','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'80c9c55d7f0a824cb5f104bde2a71a030295cf48','80c9c55d7f0a824cb5f104bde2a71a030295cf48','212834962','Merge pull request #21 from Saga-Learning-to-Hero/feat/SAGA-37-improve-lecturer-ui-components\n\nfeat: [FE][SAGA-37] Hoàn thiện giao diện dashboard và màu biểu đồ','2026-09-04 09:37:43.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:40.695544','2026-09-08 04:23:51.791341'),('1db3aa2c-b653-4b70-bee4-d325ff0995d8','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'8225f40146549083f4dd7c9d290ac9b82e9e17fa','8225f40146549083f4dd7c9d290ac9b82e9e17fa','212834962','feat: [FE][SAGA-36] Hoàn thiện trang chi tiết môn học (Subject Details) chuẩn FLM và Mock Login','2026-09-02 03:50:26.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:36.821999','2026-09-08 04:23:45.947098'),('810869c9-6ab0-496f-8dde-bae6ae10c181','083b44b6-1db8-47f7-8321-00b51054b0fa','551aa464-883a-44d3-8b19-6890d4c31f8b','8674e6aebc27406464c01172e9ac1edbb635983e','8674e6aebc27406464c01172e9ac1edbb635983e','lehai170504','feat: [FE][SAGA-54] Hoan thien tich hop da danh tinh Jira GitHub va xu ly OAuth redirect callback','2026-09-08 18:25:52.000000',NULL,NULL,NULL,NULL,NULL,'feat/SAGA-43-admin-academic-course-roster',NULL,'2026-09-08 11:26:05.027559','2026-09-09 04:59:25.008594'),('47b10ae2-959b-4609-9b87-f097039509bf','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'8a36d51233b89ccffb9aad72bc58e4731926a08c','8a36d51233b89ccffb9aad72bc58e4731926a08c','212834962','fix: [FE][SAGA-37] Đồng bộ cấu hình Next.js theo nhánh dev','2026-09-03 14:05:54.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:37.853995','2026-09-08 04:23:47.005880'),('1fa6319a-1393-4c63-9ce0-9f6165601ce2','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'8d5c8007f174cf79af8401d1b30c0d74d4563a54','8d5c8007f174cf79af8401d1b30c0d74d4563a54','212834962','Merge branch \'feat/SAGA-27-student-jira-tasks\' of https://github.com/Saga-Learning-to-Hero/saga-fe into dev\n\n# Conflicts:\n#	src/components/layout/sidebar/nav-config.ts','2026-08-28 03:13:59.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:29.331034','2026-09-08 04:23:46.022682'),('110b413c-f5b6-4676-a2a2-e671b2a4b1ab','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'8e994922074b7f0e2bfbb5c9d3349413b57a0b64','8e994922074b7f0e2bfbb5c9d3349413b57a0b64','169630551','chore: [FE][SAGA-37] Cập nhật danh tính tác giả commit','2026-09-03 14:20:14.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:38.112149','2026-09-08 04:23:45.568342'),('d48dbf76-e4da-4e3a-8cb4-d01f44d4ddb6','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'952924c0f7398b1870c8e43516ed78f55275dc8e','952924c0f7398b1870c8e43516ed78f55275dc8e','212834962','feat: [FE][SAGA-45] Tich hop TanStack Query hooks, API Services va bo Unit Tests dat 100% pass','2026-09-07 04:36:28.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:43.276138','2026-09-08 04:23:51.035201'),('0dc9ec9e-a214-4469-b16c-7def41a83a8e','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'95439533b479a83991eb19f2f0808d824f845c33','95439533b479a83991eb19f2f0808d824f845c33','212834962','feat: [FE][SAGA-35] Hoan thien he thong Top Header Navigation 2 tang cho Lecturer va Student, duy tri Sidebar cho Admin va tich hop Global Command Search','2026-09-01 02:07:46.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:36.305750','2026-09-08 04:23:45.414000'),('de3992d3-7af9-4af2-bc5f-0146d7998fc9','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'99d9adb63b243c524009819fb0baa6d9e8f61467','99d9adb63b243c524009819fb0baa6d9e8f61467','169630551','feat: hoàn thiện cấu hình trọng số lớp học','2026-08-29 18:17:16.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:34.497822','2026-09-08 04:23:51.489304'),('37637cc4-83e4-40c3-a9b5-4e5a65eb37ab','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'9d32cd0b6789740db88ceb040dcd324ebaf7b4b8','9d32cd0b6789740db88ceb040dcd324ebaf7b4b8','193885660','Merge branch \'feat/SAGA-28-student-Github-commit\' of https://github.com/Saga-Learning-to-Hero/saga-fe into dev','2026-08-29 04:35:55.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:30.105355','2026-09-08 04:23:46.476757'),('bbf8d45c-e1d6-4b5c-8c66-cbc173a18c79','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'a3b450f62045765330cb2d51dac353b71af4aac9','a3b450f62045765330cb2d51dac353b71af4aac9','Zedhuynh0210','feat: [FE][SAGA-51] Thêm bảng thông tin nhóm và thành viên','2026-09-07 22:31:22.000000',NULL,NULL,NULL,NULL,NULL,'feat/SAGA-52-lecturer-course-and-team-management',NULL,'2026-09-08 01:23:08.260518','2026-09-08 08:54:05.573308'),('3e5ce35f-7756-4883-ba4f-cc7485e7b65f','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'a64ecf226131cd597d738e6ccfd12769c0ffc5d4','a64ecf226131cd597d738e6ccfd12769c0ffc5d4','212834962','Merge branch \'dev\' of https://github.com/Saga-Learning-to-Hero/saga-fe into feat/SAGA-43-admin-academic-course-roster','2026-09-07 09:10:47.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:45.083103','2026-09-08 04:23:46.703846'),('7489c06f-f65c-4354-8e9c-3b04b479ba2a','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'a6f5201530411cdca09b7230de4243fc004ff9f9','a6f5201530411cdca09b7230de4243fc004ff9f9','212834962','feat: [FE][SAGA-32] Dong bo UI trang Khoa hoc cua Giang vien va tich hop Trung tam giam sat Do thi SNA','2026-08-29 05:42:20.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:31.396135','2026-09-08 04:23:48.293096'),('b044a74a-a8eb-4585-9bd7-f0073467519c','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'a7f8b513a813d978a517bb73dd6ab43e28d0bc17','a7f8b513a813d978a517bb73dd6ab43e28d0bc17','buiminh20','feat: [FE][SAGA-52] Tích hợp API lớp học phần, import nhóm Excel và nhóm sinh viên\n\nKết nối lecturer courses/roster/teams và student courses/team theo session; trang thông tin dự án lấy thành viên từ GET /api/student/courses/{courseId}/team thay vì mock.\n\nCo-authored-by: Cursor <cursoragent@cursor.com>','2026-09-07 23:31:01.000000',NULL,NULL,NULL,NULL,NULL,'feat/SAGA-51-Integrating-Jira-GitHub-Workspace',NULL,'2026-09-08 01:44:23.591139','2026-09-08 13:13:43.640088'),('e6b6cdbd-988c-4968-8d6d-45bfcc658d87','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'a96e73dadad0a8836d0d9b45c6b3eadc6dc2db3a','a96e73dadad0a8836d0d9b45c6b3eadc6dc2db3a','212834962','feat: [FE][SAGA-26] Nang cap hoan thien toan dien UI/UX Landing Page va Theme Toggle','2026-08-27 03:39:56.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:26.748029','2026-09-08 04:23:51.715912'),('354b8b2c-62e4-4292-a315-66d085e835bd','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'a99dee9ae260870fc2702cb42169ca30fb783d05','a99dee9ae260870fc2702cb42169ca30fb783d05','212834962','feat: [FE][SAGA-3] Implement Mock Auth Store va Auth Guard\n\n- Tao type dinh nghia Role (\'ADMIN\' | \'LECTURER\' | \'STUDENT\') va User interface tai src/types/auth.ts\n- Xay dung useAuthStore voi Zustand (persist middleware): isAuthenticated, user, login(), logout(), switchRole()\n- Ket noi Login Page voi useAuthStore: form submit va Google button goi login() thay vi alert\n- Them Auth Guard: useEffect redirect ve /dashboard neu da authenticated\n- Mock user: Le Hoang Hai, role STUDENT, avatar DiceBear','2026-08-25 07:28:27.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:23.389801','2026-09-08 04:23:46.325114'),('613fd221-a8ed-4779-a125-be5834a1af97','083b44b6-1db8-47f7-8321-00b51054b0fa','80ffd344-5190-4373-a2fb-10e74d64e55d','ac49ca35bf5f9c51276ab2caa5d81e3ef38b20a6','ac49ca35bf5f9c51276ab2caa5d81e3ef38b20a6','Zedhuynh0210','feat: [FE][SAGA-55] API GET hiển thị cấu hình dự án','2026-09-08 22:55:11.000000',NULL,NULL,NULL,NULL,NULL,'feat/SAGA-43-admin-academic-course-roster',NULL,'2026-09-08 15:55:17.414270','2026-09-09 04:59:25.252779'),('67c80ebc-9154-4f5d-9450-22ab32e3e5e5','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'aec44c17694405098e337c1855eb08166e08b5a8','aec44c17694405098e337c1855eb08166e08b5a8','212834962','Merge branch \'feat/SAGA-42-auth-service-unit-tests\' of https://github.com/Saga-Learning-to-Hero/saga-fe into dev','2026-09-06 14:29:37.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:42.502306','2026-09-08 04:23:47.989628'),('5eebf7c2-2224-40fb-87c7-e9720295b394','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'b3aef614187611aaf97a093404f5931741c3a790','b3aef614187611aaf97a093404f5931741c3a790','212834962','Merge branch \'feat/SAGA-39-student-fix\' of https://github.com/Saga-Learning-to-Hero/saga-fe into dev','2026-09-03 15:01:37.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:39.144720','2026-09-08 04:23:47.763237'),('d4db965e-b3dd-488b-9021-2e8f4398375b','083b44b6-1db8-47f7-8321-00b51054b0fa','551aa464-883a-44d3-8b19-6890d4c31f8b','b4fa4dc47b00ffdb8fdf29cf892231a3ca5ef5a1','b4fa4dc47b00ffdb8fdf29cf892231a3ca5ef5a1','lehai170504','feat: [FE][SAGA-53] Tich hop API them thu cong sinh vien vao lop hoc phan va toi uu hoa toolbar action','2026-09-08 09:15:35.000000',NULL,NULL,NULL,NULL,NULL,'feat/SAGA-43-admin-academic-course-roster',NULL,'2026-09-08 02:15:47.294203','2026-09-09 04:59:25.497132'),('036a8ec8-6a67-4555-b573-4718394d0ae9','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'b7362f88707e5a113fa7a01692bf538bbc4a49bf','b7362f88707e5a113fa7a01692bf538bbc4a49bf','212834962','feat: [FE][SAGA-37] Hoàn thiện giao diện giảng viên và điều hướng dự án nhóm','2026-09-03 13:59:46.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:37.595849','2026-09-08 04:23:45.261106'),('85515c80-81ef-43eb-ba34-cbbaceebbd65','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'c24e5ef734775de9c10cbde926a466c53fd472b4','c24e5ef734775de9c10cbde926a466c53fd472b4','147216351','feat: [FE][SAGA-39] Sửa UI/UX cho student','2026-09-03 14:56:14.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:38.628552','2026-09-08 04:23:48.671056'),('72af8a52-5d7d-4ae3-bc46-9a4373199291','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'c3fbabdbd09092c37c9d3a96af80fe2d2a3ef8b8','c3fbabdbd09092c37c9d3a96af80fe2d2a3ef8b8','212834962','feat: [FE][SAGA-35] Tach rieng trang Ho so ca nhan va trang Tich hop Jira GitHub, toi uu menu dieu huong theo tung vai tro','2026-09-01 02:14:57.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:36.563756','2026-09-08 04:23:48.217407'),('b14c4923-e4c9-4a8b-874b-d43c1b5b1995','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'c7dbbc30e32cb73c632999f3a77d962878572217','c7dbbc30e32cb73c632999f3a77d962878572217','212834962','Merge branch \'feat/SAGA-15-classspace\' of https://github.com/Saga-Learning-to-Hero/saga-fe into dev\n\n# Conflicts:\n#	src/features/auth/store/useAuthStore.ts','2026-08-27 13:50:01.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:27.522730','2026-09-08 04:23:49.879094'),('65d48683-5c60-40dc-9b79-ac6bfcb923ee','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'c815c3fc9847b8db301fa8ab3400af89f5ae3632','c815c3fc9847b8db301fa8ab3400af89f5ae3632','212834962','feat: [FE][SAGA-4] Refactor App Shell Sidebar va Hoan thien Design System Academic Tech\n\n- Modular hoa Sidebar: tach thanh sidebar-nav, sidebar-user-profile, nav-config\n- Toi uu layout SaaS: gom User Profile va Theme Toggle lam chan de o footer\n- Bo Header thua, mo rong Sidebar len w-64 thoang dang va lien mach\n- Cap nhat Design System Academic Tech: Font Plus Jakarta Sans + JetBrains Mono\n- Bang mau Deep Indigo (#4F46E5), Teal Cyan (#06B6D4) va Obsidian Slate (#0F172A)\n- Cap nhat nav items theo dung vai tro ADMIN, LECTURER, STUDENT\n- Cap nhat quy chuan saga-frontend.md','2026-08-26 02:54:17.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:24.164578','2026-09-08 04:23:47.913885'),('4437e1ad-3c6b-48de-b804-174bf2f5d564','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'ca1c6108dddfe8637b6753a658f9df3c68dd7013','ca1c6108dddfe8637b6753a658f9df3c68dd7013','147216351','feat: [FE][SAGA-39] Hoàn thiện fix UI/UX cho student','2026-09-06 04:57:10.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:41.211945','2026-09-08 04:23:46.854610'),('ac2f47bb-f388-4df8-aa9c-d1ae60dd3bdd','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'cbfc66c1f38f983141297677f6bc53996b0386ae','cbfc66c1f38f983141297677f6bc53996b0386ae','212834962','Merge pull request #1 from Saga-Learning-to-Hero/chore/SAGA-1-setup-core-libraries\n\nChore/saga 1 setup core libraries','2026-08-24 03:40:08.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:22.871491','2026-09-08 04:23:49.425278'),('87508526-d573-4f7b-baa5-47cd97900325','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'cc4ec46861360eb9aafc4dd0587a138264fda4ea','cc4ec46861360eb9aafc4dd0587a138264fda4ea','212834962','Merge pull request #9 from Saga-Learning-to-Hero/feat/SAGA-32-lecturer-sna-graph\n\nfeat: [FE][SAGA-32] Dong bo UI trang Khoa hoc cua Giang vien va tich …','2026-08-29 05:44:01.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:32.172517','2026-09-08 04:23:48.897667'),('9e23995c-150c-4a1e-a0e8-7aa4d368fe07','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'cd94b478ee521def952b264e297a3dbdb10f3e2d','cd94b478ee521def952b264e297a3dbdb10f3e2d','212834962','feat: [FE][SAGA-48] Tich hop API Lop hoc phan, Roster sinh vien va toi uu hoa toc do tai trang 0ms','2026-09-07 04:37:29.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:44.050088','2026-09-08 04:23:49.124126'),('6c89f3ff-bcbf-434d-b96e-0bd01bc4e31e','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'cf5cc356736e2c88ebb6b82842a3bfed6dd8b018','cf5cc356736e2c88ebb6b82842a3bfed6dd8b018','lehai170504','Merge pull request #24 from Saga-Learning-to-Hero/feat/SAGA-53-integrate-admin-lecturers-api\n\nFeat/saga 53 integrate admin lecturers api','2026-09-08 09:16:12.000000',NULL,NULL,NULL,NULL,NULL,'main',NULL,'2026-09-08 02:16:45.856556','2026-09-08 02:16:45.856576'),('7ef6844a-457f-4046-8849-986d106d5a8c','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'cfc2dcb215d0b913f7ce64fa8d4783d1f1a45a3c','cfc2dcb215d0b913f7ce64fa8d4783d1f1a45a3c','212834962','Chia task cho 3 dev','2026-09-06 14:01:40.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:42.243859','2026-09-08 04:23:48.519661'),('eddc3af2-4388-4160-b9e3-5cabdc95a509','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'d117cef9d552dea280b70bd5babb337488527ffa','d117cef9d552dea280b70bd5babb337488527ffa','212834962','refactor: [FE][SAGA-30] Tai cau truc he thong dinh tuyen phan quyen cho sinh vien va dong bo navigation','2026-08-29 05:22:09.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:30.879972','2026-09-08 04:23:52.095760'),('d14f6e20-bd37-4194-9ab1-1eef7154e1d3','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'d2bd73cf752fb95493dd5732de9cde0749f4bba4','d2bd73cf752fb95493dd5732de9cde0749f4bba4','212834962','chore: [FE][SAGA-42] Cap nhat phan chia toan bo phan he Admin sang Dev 1 trong tai lieu nhiem vu FE','2026-09-07 01:45:08.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:42.760284','2026-09-08 04:23:50.808889'),('9fd539fd-6b73-4661-af75-2194826dda48','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'d357503631aeb2d2ec8d51a4a22e0ad3f1bbd1db','d357503631aeb2d2ec8d51a4a22e0ad3f1bbd1db','212834962','feat: [FE][SAGA-5] Xay dung UI/UX Quan ly nguoi dung va Thay doi trang thai tai khoan (Admin)\n\n- Tao feature module src/features/admin/users/ (types, data, components)\n- Su dung 100% components thu vien shadcn/ui (Card, Table, Dialog, Button, Input, Textarea, Badge, Avatar)\n- Ho tro 4 trang thai tai khoan: ACTIVE, PENDING, INACTIVE, BANNED\n- Ma so sinh vien (studentCode) chi danh rieng cho Sinh vien (STUDENT)\n- Tich hop tim kiem, loc vai tro/trang thai va phan trang tai /admin/users\n- Build Next.js & TypeScript pass 0 error','2026-08-26 03:05:35.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:24.423365','2026-09-08 04:23:49.199475'),('96da9506-c0f6-4cf2-88dd-ee1cdc8f55de','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'d46f600bbef80ae95a3fd4a774fdca29db6b72f9','d46f600bbef80ae95a3fd4a774fdca29db6b72f9','193885660','fix: [FE][SAGA-29] Fix toan bo ESLint warnings va dong bo CustomSelect toan he thong','2026-08-29 05:05:18.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:30.363503','2026-09-08 04:23:48.973322'),('f410e5c8-7657-4f5a-b4f2-890c60cf2021','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'d4a85c78dbb4e2ab723cb413367c6a953da47c03','d4a85c78dbb4e2ab723cb413367c6a953da47c03','212834962','Merge pull request #8 from Saga-Learning-to-Hero/feat/SAGA-31-student-traceability-graph\n\nfeat: [FE][SAGA-31] Xay dung UI/UX Do thi truy xuat Traceability Grap…','2026-08-29 05:43:25.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:31.914624','2026-09-08 04:23:52.173236'),('ade1ab53-45d7-48e1-af18-7cb989a9c94f','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'d4d11a40988af0aab3cf6d0b214006649930f051','d4d11a40988af0aab3cf6d0b214006649930f051','147216351','feat: [FE][SAGA-51] Tích hợp Jira và GitHub Workspace cho cá nhân','2026-09-07 08:57:09.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:44.566205','2026-09-08 04:23:49.576203'),('776f29f8-91f6-48c9-940c-f79610ec3406','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'d60281ad7708b87bcda03cbcf86aaa4e2a471c19','d60281ad7708b87bcda03cbcf86aaa4e2a471c19','212834962','feat: [FE][SAGA-2] Xây dựng Landing Page và Login Page\n- Tạo route group (auth) và (marketing) theo Next.js App Router\n- Landing Page: tách thành 6 components (navbar, hero, why-saga, features, tech-stack, cta-footer)\n- Login Page: split-screen layout, form email/mật khẩu, toggle hiện mật khẩu, Google button UI\n- Redirect về /dashboard sau khi đăng nhập thành công\n- Cài thêm shadcn/ui Badge component\n- Fix lint: unescaped entities, useRouter thay window.location.href, unused imports\n- Fix build: Button @base-ui không hỗ trợ asChild, chuyển sang buttonVariants pattern\n- Fix scroll-behavior warning của Next.js\n- Cập nhật agent rules: quy trình lint+build bắt buộc, cấu trúc thư mục thực tế','2026-08-24 03:28:18.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:22.613456','2026-09-08 04:23:48.444130'),('769e6118-ae4f-4d82-a11b-8fb0f86b8bb3','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'d76d0dad50f6c922ac787a3ca74340bf237acd87','d76d0dad50f6c922ac787a3ca74340bf237acd87','212834962','feat: [FE][SAGA-44] Xay dung he thong TypeScript Types va Data Models cho Hoc thuat, Mon hoc va De cuong','2026-09-07 04:33:37.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:43.018186','2026-09-08 04:23:48.368712'),('cbfc39fa-66fc-42d5-b24f-4325507bedd5','083b44b6-1db8-47f7-8321-00b51054b0fa','551aa464-883a-44d3-8b19-6890d4c31f8b','d974a8bacc1dffad32c3c253a3408f46a3b2d5b4','d974a8bacc1dffad32c3c253a3408f46a3b2d5b4','lehai170504','Merge branch \'feat/SAGA-51-Integrating-Jira-GitHub-Workspace\' of https://github.com/Saga-Learning-to-Hero/saga-fe into dev','2026-09-08 08:22:55.000000',NULL,NULL,NULL,NULL,NULL,'feat/SAGA-51-Integrating-Jira-GitHub-Workspace',NULL,'2026-09-08 01:23:08.522804','2026-09-08 13:13:44.108883'),('38414731-1a6a-4fd4-8438-616740d55c86','083b44b6-1db8-47f7-8321-00b51054b0fa','551aa464-883a-44d3-8b19-6890d4c31f8b','dbb91a4dce295c67d0b97d81ba491e7797e4c876','dbb91a4dce295c67d0b97d81ba491e7797e4c876','lehai170504','fix: [FE][SAGA-53] Khac phuc header boundary roster upload, dong bo prefetchQuery va gom nhom student team cache key','2026-09-08 10:05:14.000000',NULL,NULL,NULL,NULL,NULL,'feat/SAGA-43-admin-academic-course-roster',NULL,'2026-09-08 03:05:26.176589','2026-09-09 04:59:25.741599'),('fa893c43-ec4f-41c8-9d06-8df4c8c2507b','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'e03dfa337161a3edbfb1f02a3b60a89c4836be95','e03dfa337161a3edbfb1f02a3b60a89c4836be95','212834962','Merge pull request #19 from Saga-Learning-to-Hero/feat/SAGA-40-redesign-graph-student-lecturer\n\nfeat: [FE][SAGA-40] Tai thiet ke do thi truy xuat va mang luoi SNA ch…','2026-09-03 16:19:16.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:39.662001','2026-09-08 04:23:52.474761'),('0ec95c1c-5e22-43d6-a2f9-e4f50709da56','083b44b6-1db8-47f7-8321-00b51054b0fa','551aa464-883a-44d3-8b19-6890d4c31f8b','e05197ee499ae893a895577cd396a4ac3e2fe340','e05197ee499ae893a895577cd396a4ac3e2fe340','lehai170504','Merge branch \'feat/SAGA-52-lecturer-course-and-team-management\' of https://github.com/Saga-Learning-to-Hero/saga-fe into dev\n\n# Conflicts:\n#	src/features/student/project/components/project-info-view.tsx\n#	src/features/student/project/components/team-members-card.tsx','2026-09-08 08:41:09.000000',NULL,NULL,NULL,NULL,NULL,'feat/SAGA-51-Integrating-Jira-GitHub-Workspace',NULL,'2026-09-08 01:44:23.795995','2026-09-08 13:13:44.577443'),('ca1a7693-f48e-4754-b626-740606bc16dd','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'e2a6758252d2edfeedcd58e5e812c319020942af','e2a6758252d2edfeedcd58e5e812c319020942af','212834962','style: [FE][SAGA-4] Fix indentation trong header.tsx','2026-08-25 07:53:57.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:23.906360','2026-09-08 04:23:50.430898'),('51a0f9ce-7f65-498c-90ff-ff71b4c8608d','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'e38aa670d85b8567068f6b84e3f01d7caf3d6ef8','e38aa670d85b8567068f6b84e3f01d7caf3d6ef8','169630551','UI course page','2026-08-26 06:30:33.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:25.714585','2026-09-08 04:23:47.385205'),('63d4d42f-8db8-411b-a4fd-9284de73edcb','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'e71385c567505e8c886631e858b8bee4d381e62e','e71385c567505e8c886631e858b8bee4d381e62e','212834962','chore: [FE][SAGA-1] Cài đặt thư viện cốt lõi và cấu hình design system\n- Khởi tạo shadcn/ui (components.json, Tailwind v4)\n- Cài đặt zustand, @tanstack/react-query, axios\n- Cài đặt cytoscape, @types/cytoscape, lucide-react\n- Thiết lập QueryClientProvider bọc ngoài toàn app\n- Xây dựng SAGA Design System trong globals.css (CSS variables, tokens màu, typography, shadow)\n- Chuyển font sang Inter + JetBrains Mono (next/font/google, hỗ trợ tiếng Việt)\n- Cấu hình dark mode theo system preference','2026-08-24 02:00:59.000000',NULL,NULL,NULL,NULL,NULL,'chore/SAGA-1-setup-core-libraries',NULL,'2026-09-08 02:16:22.096163','2026-09-08 04:23:42.771801'),('5637ae23-f109-49ea-8056-1a4187972877','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'e9117f80e84ce43c4c6d535a12ffd6a07ac0cb0e','e9117f80e84ce43c4c6d535a12ffd6a07ac0cb0e','169630551','feat: [FE][SAGA-16] hoàn thiện giao diện tổng quan lớp học giảng viên','2026-08-27 14:53:54.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:28.555510','2026-09-08 04:23:47.536599'),('57960956-72a1-4d88-9cd9-3396f69b4ee7','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'eac20bc561ad5557ea70c447f81f07fde1b206d9','eac20bc561ad5557ea70c447f81f07fde1b206d9','169630551','feat: hoàn thiện cấu hình trọng số lớp học','2026-08-29 18:17:16.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:34.756191','2026-09-08 04:23:47.612049'),('cababdb2-eaeb-4eb4-ad2d-787edb771fb3','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'ee56e55054753feb08f1a86796f9c2930ee747c7','ee56e55054753feb08f1a86796f9c2930ee747c7','169630551','feat: hoàn thiện giao diện chọn lớp dành cho giảng viên','2026-08-29 09:12:09.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:32.947707','2026-09-08 04:23:50.506173'),('8dcc9332-1952-4bb0-8771-dac029128176','083b44b6-1db8-47f7-8321-00b51054b0fa','551aa464-883a-44d3-8b19-6890d4c31f8b','ef9ed39528e4fefd34022550c31c1acbfbc30982','ef9ed39528e4fefd34022550c31c1acbfbc30982','lehai170504','feat: [FE][SAGA-45] Toi uu bo cuc Thong tin Du an, chuan hoa route Danh gia va dong bo OpenAPI 3.1','2026-09-09 11:18:12.000000',NULL,NULL,NULL,NULL,NULL,'feat/SAGA-43-admin-academic-course-roster',NULL,'2026-09-09 04:18:22.944139','2026-09-09 04:59:25.985957'),('ddea212b-2a20-4cba-853d-4c49f8c0af8b','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'f1243edcd2b3a57757eb83406dcf4496e107c240','f1243edcd2b3a57757eb83406dcf4496e107c240','212834962','Merge pull request #2 from Saga-Learning-to-Hero/feat/SAGA-2-login-landing-page\n\nfeat: [FE][SAGA-2] Xây dựng Landing Page và Login Page','2026-08-24 03:40:36.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:23.129862','2026-09-08 04:23:51.413358'),('35b6c8ae-b4d3-4c91-b61a-0efb44b8f39a','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'f128faf5f139f0cfc54ecfa590f95cec57cff0ae','f128faf5f139f0cfc54ecfa590f95cec57cff0ae','169630551','Merge remote-tracking branch \'origin/dev\' into feat/SAGA-16-lecturer-class-overview\n\n# Conflicts:\n#	src/app/(auth)/login/page.tsx\n#	src/app/(dashboard)/dashboard/page.tsx\n#	src/components/layout/sidebar/nav-config.ts\n#	src/features/auth/store/useAuthStore.ts','2026-08-27 14:21:19.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:28.039178','2026-09-08 04:23:46.400786'),('1fc287ad-204e-4e1b-b6f2-406cd6bc0ed7','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'f1c35345b9d0622532ab3b284c32ec5db6d19578','f1c35345b9d0622532ab3b284c32ec5db6d19578','212834962','Merge branch \'feat/SAGA-19-lecturer-class-weight-config\' of https://github.com/Saga-Learning-to-Hero/saga-fe into dev\n\n# Conflicts:\n#	src/components/layout/sidebar/nav-config.ts','2026-08-30 01:24:06.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:35.531211','2026-09-08 04:23:46.098390'),('3ee40e61-c2a8-4b2f-9a58-1b626f54318b','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'f475dd0f1a9c0131f97368e305f00964d28046c1','f475dd0f1a9c0131f97368e305f00964d28046c1','lehai170504','Revert \"Feat/saga 53 integrate admin lecturers api\"','2026-09-08 09:16:35.000000',NULL,NULL,NULL,NULL,NULL,'main',NULL,'2026-09-08 02:16:41.802461','2026-09-08 02:20:02.778778'),('c6d48380-203e-4eb3-9321-4b4c59c26d4a','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'f913e3cb26345eedc419b935f99d0f6346a500d9','f913e3cb26345eedc419b935f99d0f6346a500d9','212834962','Merge pull request #5 from Saga-Learning-to-Hero/feat/SAGA-16-lecturer-class-overview\n\nFeat/saga 16 lecturer class overview','2026-08-28 03:05:07.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:29.073054','2026-09-08 04:23:50.204373'),('081b14e7-c125-4afe-9b86-a8a078d7af5d','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'fabf82b20631ed873d53b3237f8de66e417ae266','fabf82b20631ed873d53b3237f8de66e417ae266','212834962','Merge pull request #14 from Saga-Learning-to-Hero/feat/SAGA-18-lecturer-active-team-project-view\n\nFeat/saga 18 lecturer active team project view','2026-08-30 01:22:08.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:35.273003','2026-09-08 04:23:45.337501'),('ae54ee19-7dab-419a-aae8-3ae322bedfcc','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'fb2cea7c469f0a15c1bd1655abea4f02bc5e465b','fb2cea7c469f0a15c1bd1655abea4f02bc5e465b','212834962','Merge pull request #18 from Saga-Learning-to-Hero/feat/SAGA-37-improve-lecturer-ui-components\n\nFeat/saga 37 improve lecturer UI components','2026-09-03 15:00:11.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:38.886574','2026-09-08 04:23:49.651876'),('dd9503ed-7bf8-4631-b142-3f488c757ef3','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'fb81ca25e5755b487d9d3ce9410e3389e2a863a0','fb81ca25e5755b487d9d3ce9410e3389e2a863a0','212834962','feat: [FE][SAGA-31] Xay dung UI/UX Do thi truy xuat Traceability Graph va Bang ma tran doi soat cho Sinh vien.','2026-08-29 05:37:33.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:31.138280','2026-09-08 04:23:51.337506'),('342f9426-dbeb-45f5-bd14-4ed8c3b53c97','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'fd1df1f4308e1d5c678ae82ff974c6d136e51ce4','fd1df1f4308e1d5c678ae82ff974c6d136e51ce4','169630551','feat: [FE][SAGA-15] complete lecturer class selection flow','2026-08-27 13:25:05.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:27.264549','2026-09-08 04:23:46.249455'),('4c22155a-a592-4d5b-83a4-c2951a8ad0bc','083b44b6-1db8-47f7-8321-00b51054b0fa',NULL,'ff7ae9a3fc80708bc3bd9baf4c6aef52fc2fd8e1','ff7ae9a3fc80708bc3bd9baf4c6aef52fc2fd8e1','169630551','Merge remote-tracking branch \'origin/dev\' into feat/SAGA-16-lecturer-class-overview\n\n# Conflicts:\n#	src/features/auth/store/useAuthStore.ts','2026-08-27 14:49:11.000000',NULL,NULL,NULL,NULL,NULL,'dev',NULL,'2026-09-08 02:16:28.297473','2026-09-08 04:23:47.308631');
/*!40000 ALTER TABLE `git_commit` ENABLE KEYS */;
SET @@SESSION.SQL_LOG_BIN = @MYSQLDUMP_TEMP_LOG_BIN;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-09-13 19:58:35
-- MySQL dump 10.13  Distrib 8.0.40, for Win64 (x86_64)
--
-- Host: saga-mysql-dev-saga-learning-to-hero-dev.g.aivencloud.com    Database: saga_dev
-- ------------------------------------------------------
-- Server version	8.4.8

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
SET @MYSQLDUMP_TEMP_LOG_BIN = @@SESSION.SQL_LOG_BIN;
SET @@SESSION.SQL_LOG_BIN= 0;

--
-- GTID state at the beginning of the backup 
--

SET @@GLOBAL.GTID_PURGED=/*!80000 '+'*/ '1919aa18-a7bb-11f1-81bc-d6392ca842b3:1-914,
2b117fa4-a50b-11f1-94dc-4693fd8fc2f3:1-19,
40a37216-a6ab-11f1-8242-c68388d978d4:1-15,
706e5ee9-9f98-11f1-9cf2-ce9be0ce648e:1-92,
92570e28-a103-11f1-9392-0e34d3ade6a9:1-38,
b460b840-a38a-11f1-b5cf-5617d7f8921d:1-71,
ce0386b2-9ea7-11f1-ad68-9266917ecf9e:1-38';

--
-- Table structure for table `task_git_commit_link`
--

DROP TABLE IF EXISTS `task_git_commit_link`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `task_git_commit_link` (
  `id` char(36) NOT NULL,
  `task_id` char(36) NOT NULL,
  `git_commit_id` char(36) NOT NULL,
  `link_source` varchar(32) NOT NULL,
  `jira_key_snapshot` varchar(64) DEFAULT NULL,
  `confidence` varchar(16) DEFAULT NULL,
  `metadata_json` json DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_git_commit_link` (`task_id`,`git_commit_id`),
  KEY `ix_task_commit_commit` (`git_commit_id`),
  CONSTRAINT `fk_task_commit_commit` FOREIGN KEY (`git_commit_id`) REFERENCES `git_commit` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_task_commit_task` FOREIGN KEY (`task_id`) REFERENCES `task` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `task_git_commit_link`
--
-- WHERE:  git_commit_id IN (SELECT id FROM git_commit WHERE repo_id='083b44b6-1db8-47f7-8321-00b51054b0fa')

-- MySQL dump 10.13  Distrib 8.0.40, for Win64 (x86_64)
--
-- Host: saga-mysql-dev-saga-learning-to-hero-dev.g.aivencloud.com    Database: saga_dev
-- ------------------------------------------------------
-- Server version	8.4.8

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
SET @MYSQLDUMP_TEMP_LOG_BIN = @@SESSION.SQL_LOG_BIN;
SET @@SESSION.SQL_LOG_BIN= 0;

--
-- GTID state at the beginning of the backup 
--

SET @@GLOBAL.GTID_PURGED=/*!80000 '+'*/ '1919aa18-a7bb-11f1-81bc-d6392ca842b3:1-914,
2b117fa4-a50b-11f1-94dc-4693fd8fc2f3:1-19,
40a37216-a6ab-11f1-8242-c68388d978d4:1-15,
706e5ee9-9f98-11f1-9cf2-ce9be0ce648e:1-92,
92570e28-a103-11f1-9392-0e34d3ade6a9:1-38,
b460b840-a38a-11f1-b5cf-5617d7f8921d:1-71,
ce0386b2-9ea7-11f1-ad68-9266917ecf9e:1-38';

--
-- Table structure for table `commit_review_intent`
--

DROP TABLE IF EXISTS `commit_review_intent`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `commit_review_intent` (
  `id` char(36) NOT NULL,
  `git_repo_id` char(36) NOT NULL,
  `git_commit_id` char(36) NOT NULL,
  `sha_hash` varchar(64) NOT NULL,
  `review_mode` varchar(32) NOT NULL,
  `priority` varchar(16) NOT NULL,
  `priority_rank` int NOT NULL,
  `intent_status` varchar(32) NOT NULL,
  `ai_job_id` char(36) DEFAULT NULL,
  `review_policy_version` varchar(64) DEFAULT NULL,
  `last_job_status` varchar(32) DEFAULT NULL,
  `started_at` datetime(6) DEFAULT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `safe_error_code` varchar(64) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_commit_review_intent_repo_sha` (`git_repo_id`,`sha_hash`),
  KEY `fk_review_intent_commit` (`git_commit_id`),
  CONSTRAINT `fk_review_intent_commit` FOREIGN KEY (`git_commit_id`) REFERENCES `git_commit` (`id`),
  CONSTRAINT `fk_review_intent_repo` FOREIGN KEY (`git_repo_id`) REFERENCES `git_repo` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `commit_review_intent`
--
-- WHERE:  git_repo_id='083b44b6-1db8-47f7-8321-00b51054b0fa'

/*!40000 ALTER TABLE `commit_review_intent` DISABLE KEYS */;
/*!40000 ALTER TABLE `commit_review_intent` ENABLE KEYS */;
SET @@SESSION.SQL_LOG_BIN = @MYSQLDUMP_TEMP_LOG_BIN;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-09-13 19:58:40
-- MySQL dump 10.13  Distrib 8.0.40, for Win64 (x86_64)
--
-- Host: saga-mysql-dev-saga-learning-to-hero-dev.g.aivencloud.com    Database: saga_dev
-- ------------------------------------------------------
-- Server version	8.4.8

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
SET @MYSQLDUMP_TEMP_LOG_BIN = @@SESSION.SQL_LOG_BIN;
SET @@SESSION.SQL_LOG_BIN= 0;

--
-- GTID state at the beginning of the backup 
--

SET @@GLOBAL.GTID_PURGED=/*!80000 '+'*/ '1919aa18-a7bb-11f1-81bc-d6392ca842b3:1-914,
2b117fa4-a50b-11f1-94dc-4693fd8fc2f3:1-19,
40a37216-a6ab-11f1-8242-c68388d978d4:1-15,
706e5ee9-9f98-11f1-9cf2-ce9be0ce648e:1-92,
92570e28-a103-11f1-9392-0e34d3ade6a9:1-38,
b460b840-a38a-11f1-b5cf-5617d7f8921d:1-71,
ce0386b2-9ea7-11f1-ad68-9266917ecf9e:1-38';

--
-- Table structure for table `commit_review_result`
--

DROP TABLE IF EXISTS `commit_review_result`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `commit_review_result` (
  `id` char(36) NOT NULL,
  `intent_id` char(36) NOT NULL,
  `ai_job_id` char(36) NOT NULL,
  `project_id` char(36) NOT NULL,
  `git_repo_id` char(36) NOT NULL,
  `git_commit_id` char(36) NOT NULL,
  `sha_hash` varchar(64) NOT NULL,
  `policy_version` varchar(64) NOT NULL,
  `review_mode` varchar(32) NOT NULL,
  `traceability_status` varchar(32) NOT NULL,
  `message_quality` varchar(16) NOT NULL,
  `code_quality` varchar(32) NOT NULL,
  `inferred_function_label` varchar(32) DEFAULT NULL,
  `inferred_function_confidence` varchar(16) DEFAULT NULL,
  `task_alignment` varchar(32) NOT NULL,
  `verdict_eligible` tinyint(1) NOT NULL,
  `verdict` varchar(32) NOT NULL,
  `overall_status` varchar(32) NOT NULL,
  `schema_version` varchar(64) NOT NULL,
  `findings_json` mediumtext,
  `evidence_refs_json` mediumtext,
  `completed_at` datetime(6) NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_commit_review_result_intent` (`intent_id`),
  UNIQUE KEY `uk_commit_review_result_job` (`ai_job_id`),
  KEY `fk_review_result_repo` (`git_repo_id`),
  KEY `fk_review_result_commit` (`git_commit_id`),
  KEY `fk_review_result_project` (`project_id`),
  CONSTRAINT `fk_review_result_commit` FOREIGN KEY (`git_commit_id`) REFERENCES `git_commit` (`id`),
  CONSTRAINT `fk_review_result_intent` FOREIGN KEY (`intent_id`) REFERENCES `commit_review_intent` (`id`),
  CONSTRAINT `fk_review_result_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`),
  CONSTRAINT `fk_review_result_repo` FOREIGN KEY (`git_repo_id`) REFERENCES `git_repo` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `commit_review_result`
--
-- WHERE:  git_repo_id='083b44b6-1db8-47f7-8321-00b51054b0fa'

/*!40000 ALTER TABLE `commit_review_result` DISABLE KEYS */;
/*!40000 ALTER TABLE `commit_review_result` ENABLE KEYS */;
SET @@SESSION.SQL_LOG_BIN = @MYSQLDUMP_TEMP_LOG_BIN;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-09-13 19:58:43
-- MySQL dump 10.13  Distrib 8.0.40, for Win64 (x86_64)
--
-- Host: saga-mysql-dev-saga-learning-to-hero-dev.g.aivencloud.com    Database: saga_dev
-- ------------------------------------------------------
-- Server version	8.4.8

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
SET @MYSQLDUMP_TEMP_LOG_BIN = @@SESSION.SQL_LOG_BIN;
SET @@SESSION.SQL_LOG_BIN= 0;

--
-- GTID state at the beginning of the backup 
--

SET @@GLOBAL.GTID_PURGED=/*!80000 '+'*/ '1919aa18-a7bb-11f1-81bc-d6392ca842b3:1-915,
2b117fa4-a50b-11f1-94dc-4693fd8fc2f3:1-19,
40a37216-a6ab-11f1-8242-c68388d978d4:1-15,
706e5ee9-9f98-11f1-9cf2-ce9be0ce648e:1-92,
92570e28-a103-11f1-9392-0e34d3ade6a9:1-38,
b460b840-a38a-11f1-b5cf-5617d7f8921d:1-71,
ce0386b2-9ea7-11f1-ad68-9266917ecf9e:1-38';

--
-- Table structure for table `task_git_commit_link`
--

DROP TABLE IF EXISTS `task_git_commit_link`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `task_git_commit_link` (
  `id` char(36) NOT NULL,
  `task_id` char(36) NOT NULL,
  `git_commit_id` char(36) NOT NULL,
  `link_source` varchar(32) NOT NULL,
  `jira_key_snapshot` varchar(64) DEFAULT NULL,
  `confidence` varchar(16) DEFAULT NULL,
  `metadata_json` json DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_git_commit_link` (`task_id`,`git_commit_id`),
  KEY `ix_task_commit_commit` (`git_commit_id`),
  CONSTRAINT `fk_task_commit_commit` FOREIGN KEY (`git_commit_id`) REFERENCES `git_commit` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_task_commit_task` FOREIGN KEY (`task_id`) REFERENCES `task` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `task_git_commit_link`
--
-- WHERE:  git_commit_id IN (SELECT id FROM git_commit WHERE repo_id='083b44b6-1db8-47f7-8321-00b51054b0fa')

LOCK TABLES `task_git_commit_link` WRITE;
/*!40000 ALTER TABLE `task_git_commit_link` DISABLE KEYS */;
INSERT INTO `task_git_commit_link` (`id`, `task_id`, `git_commit_id`, `link_source`, `jira_key_snapshot`, `confidence`, `metadata_json`, `created_at`, `updated_at`) VALUES ('04399e74-8fcb-4ea5-8954-0d49879a5e7b','bc4a2a38-f827-4a76-bf09-b9baee114fa5','18850cbb-0c41-4735-b321-8889ffd99121','RECONCILIATION','SAGA-43','HIGH',NULL,'2026-09-09 04:59:23.543079','2026-09-09 04:59:23.543098'),('08419be5-5de6-4084-b533-442c9795a24e','558637d6-cbc7-477e-a681-178b98ee0155','354b8b2c-62e4-4292-a315-66d085e835bd','COMMIT_MESSAGE','SAGA-3','HIGH',NULL,'2026-09-08 04:45:02.595571','2026-09-08 04:45:02.595611'),('087eae19-69d3-439a-a9fd-a7e662a2df35','4c91f20c-7d62-4e44-bf03-2112afe1795a','4437e1ad-3c6b-48de-b804-174bf2f5d564','COMMIT_MESSAGE','SAGA-39','HIGH',NULL,'2026-09-08 04:45:12.582108','2026-09-08 04:45:12.582122'),('0a662b92-5314-44e4-bf5c-cc4700ee1452','271bd33a-f319-4cf3-b992-5cdb8af9ad33','c5250c34-88de-4bdd-95a1-9865ff5197a3','COMMIT_MESSAGE','SAGA-17','HIGH',NULL,'2026-09-08 04:45:05.659971','2026-09-08 04:45:05.660003'),('0a68f901-2cb4-4dbc-b1dc-f8720108a81b','765fbc1e-6ea2-4cd7-b10a-625d762b69ad','e46f6f0d-f7a0-4359-b937-3a334b160656','COMMIT_MESSAGE','SAGA-28','HIGH',NULL,'2026-09-08 04:45:11.179993','2026-09-08 04:45:11.180877'),('0dd64903-3a3f-4953-9265-d480c9466658','4826ac56-edd6-4db2-8ade-fef499ec9694','0ec95c1c-5e22-43d6-a2f9-e4f50709da56','COMMIT_MESSAGE','SAGA-52','HIGH',NULL,'2026-09-08 04:45:12.700211','2026-09-08 04:45:12.700226'),('119f7cc3-08fd-43aa-ba93-28017e5ff505','4c91f20c-7d62-4e44-bf03-2112afe1795a','85515c80-81ef-43eb-ba34-cbbaceebbd65','COMMIT_MESSAGE','SAGA-39','HIGH',NULL,'2026-09-08 04:45:10.582049','2026-09-08 04:45:10.582074'),('1265c6d5-0da8-48c8-8520-4d5a915a2ffb','de4a1df8-fbf9-46ac-afbe-928a5863147e','bbf8d45c-e1d6-4b5c-8c66-cbc173a18c79','COMMIT_MESSAGE','SAGA-51','HIGH',NULL,'2026-09-08 04:45:10.105492','2026-09-08 04:45:10.105522'),('13c8c92b-eede-412d-8a50-58a1f9ef29e5','6b61bb34-ea6b-4ff8-9117-cc0db2c4a5be','d48dbf76-e4da-4e3a-8cb4-d01f44d4ddb6','COMMIT_MESSAGE','SAGA-45','HIGH',NULL,'2026-09-08 04:45:10.229367','2026-09-08 04:45:10.229395'),('16d9cd74-db81-4dab-80b6-f21b1a0c277a','de4a1df8-fbf9-46ac-afbe-928a5863147e','d4db965e-b3dd-488b-9021-2e8f4398375b','RECONCILIATION','SAGA-51','HIGH',NULL,'2026-09-08 13:13:42.469304','2026-09-08 13:13:42.469327'),('173eca17-d55c-4a76-9f82-e7ba43c4a05f','1f54c37b-e137-4dcb-9384-a3dbf363a48b','67c80ebc-9154-4f5d-9450-22ab32e3e5e5','COMMIT_MESSAGE','SAGA-42','HIGH',NULL,'2026-09-08 04:45:02.361954','2026-09-08 04:45:02.361992'),('1a9a661d-1e6c-49d1-878d-1cdf753b03f6','bc4a2a38-f827-4a76-bf09-b9baee114fa5','613fd221-a8ed-4779-a125-be5834a1af97','RECONCILIATION','SAGA-43','HIGH',NULL,'2026-09-09 04:59:22.566738','2026-09-09 04:59:22.566759'),('1bdd3a47-25d6-44ee-bf21-5452018b4aef','d211176d-b178-4cf2-a327-8f502819f3cb','63d4d42f-8db8-411b-a4fd-9284de73edcb','COMMIT_MESSAGE','SAGA-1','HIGH',NULL,'2026-09-08 04:45:04.841091','2026-09-08 04:45:04.841126'),('1f10018a-55cc-41b5-9aae-ef73a3e0b057','6f5545bc-997a-4fd5-a342-331c1afdaafe','dd9503ed-7bf8-4631-b142-3f488c757ef3','COMMIT_MESSAGE','SAGA-31','HIGH',NULL,'2026-09-08 04:45:08.352175','2026-09-08 04:45:08.352207'),('2141d825-a1e5-456c-89de-5c7d2ed44267','7542f76f-3e26-45cb-b9e6-8ce03fb0a829','f88ace40-447a-466b-9892-31093bca2a1c','COMMIT_MESSAGE','SAGA-7','HIGH',NULL,'2026-09-08 04:45:04.722576','2026-09-08 04:45:04.722609'),('230d2967-1801-431e-84cc-849820aa6d36','b631cb7f-34af-4008-a072-0606dcf60c69','498b638b-a51b-47eb-8cd2-cf2ae7438c46','COMMIT_MESSAGE','SAGA-22','HIGH',NULL,'2026-09-08 04:45:03.886386','2026-09-08 04:45:03.886547'),('281ef7de-f27b-4507-b739-d541b69f2455','bc4a2a38-f827-4a76-bf09-b9baee114fa5','38414731-1a6a-4fd4-8438-616740d55c86','RECONCILIATION','SAGA-43','HIGH',NULL,'2026-09-09 04:59:22.810715','2026-09-09 04:59:22.810733'),('286f9fec-a3b9-4cdf-a780-56117a255ccb','3acc6c90-36bf-4d39-9d53-27dbfe53e5a8','47b10ae2-959b-4609-9b87-f097039509bf','COMMIT_MESSAGE','SAGA-37','HIGH',NULL,'2026-09-08 04:45:13.053787','2026-09-08 04:45:13.053817'),('28f957d5-546f-484f-a14a-6baf87bc4a7c','c924fa17-4cce-4d26-b9c1-f4c4acf1cc70','ca1a7693-f48e-4754-b626-740606bc16dd','COMMIT_MESSAGE','SAGA-4','HIGH',NULL,'2026-09-08 04:45:04.369003','2026-09-08 04:45:04.369494'),('2b45e570-be7d-4670-8683-f544f9b70745','e5cf67a9-a271-4757-97eb-ae98cbdef5b2','e7dffb71-6a5d-4549-85b5-c89f35c84447','COMMIT_MESSAGE','SAGA-38','HIGH',NULL,'2026-09-08 04:45:05.308627','2026-09-08 04:45:05.308658'),('2e050563-b00a-4ff2-b91d-0020df6d0251','bc4a2a38-f827-4a76-bf09-b9baee114fa5','db6817ae-9724-400e-aee7-b10c8c50145c','RECONCILIATION','SAGA-43','HIGH',NULL,'2026-09-09 04:59:23.787184','2026-09-09 04:59:23.787201'),('2e82fe8d-2edb-4689-af93-3fd6891bda02','3acc6c90-36bf-4d39-9d53-27dbfe53e5a8','ae54ee19-7dab-419a-aae8-3ae322bedfcc','COMMIT_MESSAGE','SAGA-37','HIGH',NULL,'2026-09-08 04:45:03.182851','2026-09-08 04:45:03.182898'),('2e89e841-e06b-4218-ad19-838c14314a95','ad09ea88-a9ea-4354-9dbc-90963a2ab10b','6c89f3ff-bcbf-434d-b96e-0bd01bc4e31e','COMMIT_MESSAGE','SAGA-53','HIGH',NULL,'2026-09-08 04:45:11.651930','2026-09-08 04:45:11.651945'),('2eb30b36-e747-493a-8b5b-dd83ebabf59c','1f54c37b-e137-4dcb-9384-a3dbf363a48b','869304cf-d3b6-423c-ab17-9f14ca891c58','COMMIT_MESSAGE','SAGA-42','HIGH',NULL,'2026-09-08 04:45:02.831207','2026-09-08 04:45:02.831245'),('32794866-cb62-4160-8e71-0706f7d7cd25','bc4a2a38-f827-4a76-bf09-b9baee114fa5','d4db965e-b3dd-488b-9021-2e8f4398375b','RECONCILIATION','SAGA-43','HIGH',NULL,'2026-09-09 04:59:24.031444','2026-09-09 04:59:24.031462'),('33052c25-085a-4285-9e5b-53c1ef5f2e92','d4c144a7-42a3-4311-8e54-63dad323dd92','6d1c0737-f827-42f4-a9a4-36683dd6d1cd','COMMIT_MESSAGE','SAGA-18','HIGH',NULL,'2026-09-08 04:45:06.717605','2026-09-08 04:45:06.717639'),('33b266c2-f016-4465-a19b-a5a1d4abec73','32e8ac9f-01ca-4e32-abf8-4a420885f81e','9df97442-3fa1-4a36-a526-f66368a1f5f9','COMMIT_MESSAGE','SAGA-24','HIGH',NULL,'2026-09-08 04:45:07.184680','2026-09-08 04:45:07.184718'),('341f9d0a-e8f7-4b91-93ea-74380a9e3e42','bc4a2a38-f827-4a76-bf09-b9baee114fa5','8dcc9332-1952-4bb0-8771-dac029128176','RECONCILIATION','SAGA-43','HIGH',NULL,'2026-09-09 04:59:23.298910','2026-09-09 04:59:23.298957'),('353f169a-15fe-455a-8339-e922aa8464df','19a08943-6375-4ae0-9f96-34aa1eb4ec47','4c22155a-a592-4d5b-83a4-c2951a8ad0bc','COMMIT_MESSAGE','SAGA-16','HIGH',NULL,'2026-09-08 04:45:07.535567','2026-09-08 04:45:07.535598'),('3754a54e-4e60-48ea-aa0d-63de989a31a9','ad09ea88-a9ea-4354-9dbc-90963a2ab10b','d4db965e-b3dd-488b-9021-2e8f4398375b','COMMIT_MESSAGE','SAGA-53','HIGH',NULL,'2026-09-08 04:45:08.468371','2026-09-08 04:45:08.468403'),('3828173c-5fc2-48dd-af53-0c698a153273','2bfbd537-5dd2-4733-8162-a51071e43e09','48d778f0-bf5d-45e9-ba3c-72302b77386c','COMMIT_MESSAGE','SAGA-34','HIGH',NULL,'2026-09-08 04:45:02.713611','2026-09-08 04:45:02.713651'),('3cf8e262-546a-4bc5-8ddf-bcc5918349be','6b6bcec6-91b6-41ea-bbc7-ba748634445c','87508526-d573-4f7b-baa5-47cd97900325','COMMIT_MESSAGE','SAGA-32','HIGH',NULL,'2026-09-08 04:45:05.543525','2026-09-08 04:45:05.543557'),('40da8a41-08d3-407e-bd68-8b2bf9d29263','3acc6c90-36bf-4d39-9d53-27dbfe53e5a8','e99d7241-3107-4439-ae1e-672d25ee510b','COMMIT_MESSAGE','SAGA-37','HIGH',NULL,'2026-09-08 04:45:07.417372','2026-09-08 04:45:07.417403'),('434f41c0-6779-4ac5-b8e0-2f7c1290a1fd','28ab8f5b-01e8-49f5-be8e-da9ff9ffa28f','5d77175c-f673-4802-be84-8f46d4f027d3','COMMIT_MESSAGE','SAGA-27','HIGH',NULL,'2026-09-08 04:45:08.002537','2026-09-08 04:45:08.002574'),('440288c1-9ff5-437e-b24b-6b17a9edb364','6b6bcec6-91b6-41ea-bbc7-ba748634445c','7489c06f-f65c-4354-8e9c-3b04b479ba2a','COMMIT_MESSAGE','SAGA-32','HIGH',NULL,'2026-09-08 04:45:05.778411','2026-09-08 04:45:05.778455'),('440b53cf-2fb2-4c44-9bed-bb24cbba6c5e','4826ac56-edd6-4db2-8ade-fef499ec9694','cbfc39fa-66fc-42d5-b24f-4325507bedd5','RECONCILIATION','SAGA-52','HIGH',NULL,'2026-09-08 08:54:04.640557','2026-09-08 08:54:04.640568'),('44f7fe52-b64a-43cc-bc8e-6d7d2cb6a286','765fbc1e-6ea2-4cd7-b10a-625d762b69ad','37637cc4-83e4-40c3-a9b5-4e5a65eb37ab','COMMIT_MESSAGE','SAGA-28','HIGH',NULL,'2026-09-08 04:45:07.068379','2026-09-08 04:45:07.068412'),('45219475-968f-4f88-9823-e708a11ad8d7','c7832589-cd35-48a2-8bc2-98b71d7386df','170914c1-7495-4a36-b25b-dd9b02e12de6','COMMIT_MESSAGE','SAGA-25','HIGH',NULL,'2026-09-08 04:45:06.363216','2026-09-08 04:45:06.363250'),('48634c8a-640f-47b9-b941-188f393f8104','f5e2984d-6d49-4c97-9b0d-806728ba95d5','ad0c9dfd-8fc9-451a-9ef7-d65a1027cafd','COMMIT_MESSAGE','SAGA-33','HIGH',NULL,'2026-09-08 04:45:10.346218','2026-09-08 04:45:10.346255'),('489e53f1-6cdb-41ed-8d19-69f90397b768','bc4a2a38-f827-4a76-bf09-b9baee114fa5','810869c9-6ab0-496f-8dde-bae6ae10c181','RECONCILIATION','SAGA-43','HIGH',NULL,'2026-09-09 04:59:23.054637','2026-09-09 04:59:23.054686'),('49b37958-582c-43f9-9d11-10cd4383e928','98af9398-cf00-4c84-ab53-b7b5ce2cfbb8','c9613a3a-790b-4664-afc3-a71552329f30','COMMIT_MESSAGE','SAGA-30','HIGH',NULL,'2026-09-08 04:45:11.535706','2026-09-08 04:45:11.535722'),('4b65286d-895b-4b65-9769-eeeb395dc731','7d8c64ec-1c22-4756-80a3-e22ea00af668','9e23995c-150c-4a1e-a0e8-7aa4d368fe07','COMMIT_MESSAGE','SAGA-48','HIGH',NULL,'2026-09-08 04:45:09.755234','2026-09-08 04:45:09.755266'),('4f64e91a-973a-4330-b3cb-fa53cce60c9c','f3615567-e83e-481c-8c88-c1b6ff5419a6','f676dcc1-2e60-45c2-9814-0c78f28f749b','COMMIT_MESSAGE','SAGA-40','HIGH',NULL,'2026-09-08 04:45:12.233001','2026-09-08 04:45:12.233016'),('50335295-10d9-4f0b-bc70-d7f9de08f4e6','ad09ea88-a9ea-4354-9dbc-90963a2ab10b','38414731-1a6a-4fd4-8438-616740d55c86','COMMIT_MESSAGE','SAGA-53','HIGH',NULL,'2026-09-08 04:45:08.702062','2026-09-08 04:45:08.702093'),('51d12ad2-d0b3-4fa8-a6d2-4998a06dcaeb','19a08943-6375-4ae0-9f96-34aa1eb4ec47','5637ae23-f109-49ea-8056-1a4187972877','COMMIT_MESSAGE','SAGA-16','HIGH',NULL,'2026-09-08 04:45:02.479224','2026-09-08 04:45:02.479263'),('51f7c18e-bc7a-4ba5-864e-417010b5974f','de4a1df8-fbf9-46ac-afbe-928a5863147e','ade1ab53-45d7-48e1-af18-7cb989a9c94f','COMMIT_MESSAGE','SAGA-51','HIGH',NULL,'2026-09-08 04:45:08.937363','2026-09-08 04:45:08.937396'),('52ed229a-2c68-43e8-be1b-27c9da97faa2','d211176d-b178-4cf2-a327-8f502819f3cb','fd531abe-0e40-4fd1-8105-76d037bfff8d','COMMIT_MESSAGE','SAGA-1','HIGH',NULL,'2026-09-08 04:45:05.192423','2026-09-08 04:45:05.192456'),('5d61b5dc-4de6-4ce8-b860-0e1a9d728c79','28ab8f5b-01e8-49f5-be8e-da9ff9ffa28f','1fa6319a-1393-4c63-9ce0-9f6165601ce2','COMMIT_MESSAGE','SAGA-27','HIGH',NULL,'2026-09-08 04:45:09.871369','2026-09-08 04:45:09.871403'),('62982ea6-4500-431b-977c-eb0808370de2','3bf24156-c817-4cd4-9b0c-b2a825853f95','d6167df9-572b-4136-bc31-f4093ffc05be','COMMIT_MESSAGE','SAGA-6','HIGH',NULL,'2026-09-08 04:45:03.417903','2026-09-08 04:45:03.417942'),('62faf3d8-fc32-462b-8627-136d222f9a22','de4a1df8-fbf9-46ac-afbe-928a5863147e','810869c9-6ab0-496f-8dde-bae6ae10c181','RECONCILIATION','SAGA-51','HIGH',NULL,'2026-09-08 13:13:41.534271','2026-09-08 13:13:41.534289'),('638dbe36-6664-4b52-9f89-28f931883ee7','756a5dcd-8540-4527-9cd0-d2af298dad56','0dc9ec9e-a214-4469-b16c-7def41a83a8e','COMMIT_MESSAGE','SAGA-35','HIGH',NULL,'2026-09-08 04:45:04.003955','2026-09-08 04:45:04.003996'),('63f575dc-98c1-497b-bd14-21380be4bbd8','4826ac56-edd6-4db2-8ade-fef499ec9694','bbf8d45c-e1d6-4b5c-8c66-cbc173a18c79','RECONCILIATION','SAGA-52','HIGH',NULL,'2026-09-08 08:54:03.941317','2026-09-08 08:54:03.941328'),('649aacb8-b001-4be4-a4d8-88f7e527747a','77bbc908-5999-414b-a803-059c13bd37fc','b1b2df1b-496a-493d-b239-51f4f5a6c890','COMMIT_MESSAGE','SAGA-36','HIGH',NULL,'2026-09-08 04:45:02.949227','2026-09-08 04:45:02.949299'),('65965135-1962-4933-bb42-739f8445eb58','de4a1df8-fbf9-46ac-afbe-928a5863147e','b044a74a-a8eb-4585-9bd7-f0073467519c','RECONCILIATION','SAGA-51','HIGH',NULL,'2026-09-08 13:13:42.001717','2026-09-08 13:13:42.001736'),('68590be6-356e-41af-bfa7-2ca8ee1d71e0','bc4a2a38-f827-4a76-bf09-b9baee114fa5','3e5ce35f-7756-4883-ba4f-cc7485e7b65f','COMMIT_MESSAGE','SAGA-43','HIGH',NULL,'2026-09-08 04:45:10.463959','2026-09-08 04:45:10.463985'),('6a0bcd93-d1d6-482a-8d0f-8475d850a041','bd168cf0-73c6-43cd-ac13-d7ad2a0be688','e6b6cdbd-988c-4968-8d6d-45bfcc658d87','COMMIT_MESSAGE','SAGA-26','HIGH',NULL,'2026-09-08 04:45:11.298096','2026-09-08 04:45:11.298145'),('6a62b678-a953-4ab4-9c57-17ed3c1c7aa5','67cc3aed-2836-447e-8409-9067109ed2d0','9fd539fd-6b73-4661-af75-2194826dda48','COMMIT_MESSAGE','SAGA-5','HIGH',NULL,'2026-09-08 04:45:13.292002','2026-09-08 04:45:13.292019'),('6a753855-bcb3-4b81-a257-0ba7f2d09c52','d222d524-ab82-48a3-8995-8301569be77d','2fb7a1cc-a79a-46c8-858a-11c53f56bb16','COMMIT_MESSAGE','SAGA-8','HIGH',NULL,'2026-09-08 04:45:05.895313','2026-09-08 04:45:05.895344'),('6dbb0ab2-5d74-4358-81b0-e6eb881d3db6','271bd33a-f319-4cf3-b992-5cdb8af9ad33','aaf1eb76-9e8d-45a4-a5f3-1112c498346f','COMMIT_MESSAGE','SAGA-17','HIGH',NULL,'2026-09-08 04:45:07.653121','2026-09-08 04:45:07.653152'),('6ec3f1c1-24ac-4ec5-9ce8-108b001d71f2','bc4a2a38-f827-4a76-bf09-b9baee114fa5','40c03b1c-18e5-4d36-bd17-4f91625443fa','COMMIT_MESSAGE','SAGA-43','HIGH',NULL,'2026-09-08 04:45:06.011836','2026-09-08 04:45:06.011901'),('7187fb1c-f1a2-40b6-8f08-b1b9e24b00ce','3acc6c90-36bf-4d39-9d53-27dbfe53e5a8','110b413c-f5b6-4676-a2a2-e671b2a4b1ab','COMMIT_MESSAGE','SAGA-37','HIGH',NULL,'2026-09-08 04:45:06.246528','2026-09-08 04:45:06.246566'),('7378f884-416a-499a-864f-b6c7a0f04ba3','4e831c3b-a60e-4619-a06e-2f1dfd97a3e1','b02d40e7-0e78-4bbd-bf18-eb3729ee837c','COMMIT_MESSAGE','SAGA-50','HIGH',NULL,'2026-09-08 04:45:07.300868','2026-09-08 04:45:07.300905'),('748500ca-7af7-4c8c-bcc4-663ed61244b9','3acc6c90-36bf-4d39-9d53-27dbfe53e5a8','c805897a-aab1-4ca6-bbf6-8803b290654a','COMMIT_MESSAGE','SAGA-37','HIGH',NULL,'2026-09-08 04:45:09.169823','2026-09-08 04:45:09.169856'),('74e9e6f6-e3c3-4d7c-99b6-d7c607834b13','bb6c9400-3663-4e51-9c3f-f629ad06dda1','769e6118-ae4f-4d82-a11b-8fb0f86b8bb3','COMMIT_MESSAGE','SAGA-44','HIGH',NULL,'2026-09-08 04:45:07.885584','2026-09-08 04:45:07.885617'),('75f002d7-304f-4fba-9539-7af0428d0473','c924fa17-4cce-4d26-b9c1-f4c4acf1cc70','65d48683-5c60-40dc-9b79-ac6bfcb923ee','COMMIT_MESSAGE','SAGA-4','HIGH',NULL,'2026-09-08 04:45:06.598846','2026-09-08 04:45:06.598878'),('76eaaa0d-65c6-45f0-94a3-c8868ac4bf8e','44b27758-e9fd-40c9-a5ff-c660f0495c52','722b4eba-ac21-4678-be36-a41b59a30ed7','COMMIT_MESSAGE','SAGA-41','HIGH',NULL,'2026-09-08 04:45:04.129378','2026-09-08 04:45:04.129414'),('770d7905-3191-4fa2-a17e-5647bbb60049','4826ac56-edd6-4db2-8ade-fef499ec9694','d4db965e-b3dd-488b-9021-2e8f4398375b','RECONCILIATION','SAGA-52','HIGH',NULL,'2026-09-08 08:54:05.106418','2026-09-08 08:54:05.106431'),('77f3c574-764e-471a-b026-df59f33ee5e3','1f54c37b-e137-4dcb-9384-a3dbf363a48b','d14f6e20-bd37-4194-9ab1-1eef7154e1d3','COMMIT_MESSAGE','SAGA-42','HIGH',NULL,'2026-09-08 04:45:04.604961','2026-09-08 04:45:04.604995'),('78b3711b-4285-423c-9e7b-12e8ed34f0dd','19a08943-6375-4ae0-9f96-34aa1eb4ec47','c6d48380-203e-4eb3-9321-4b4c59c26d4a','COMMIT_MESSAGE','SAGA-16','HIGH',NULL,'2026-09-08 04:45:08.235974','2026-09-08 04:45:08.236027'),('7cd0d709-a48b-4732-844e-7145a75f6c3a','f3615567-e83e-481c-8c88-c1b6ff5419a6','cba8e841-dd94-4ce1-baef-ad7ddf11ccf2','COMMIT_MESSAGE','SAGA-40','HIGH',NULL,'2026-09-08 04:45:12.349134','2026-09-08 04:45:12.349149'),('7d6c5c18-8c75-44a3-80db-c1188b9cd6e4','7fba3954-cf27-4a01-979d-876f93179a37','fdb8f484-9a9a-448c-9b8b-3e9b683c363e','COMMIT_MESSAGE','SAGA-15','HIGH',NULL,'2026-09-08 04:45:07.769284','2026-09-08 04:45:07.769319'),('7e866ffe-b0ab-4911-906a-add4aaf7a75f','ed726227-e6e9-4b25-9e5e-79a8505087e4','460b824d-edf3-4701-b716-00189ae8c4ee','COMMIT_MESSAGE','SAGA-9','HIGH',NULL,'2026-09-08 04:45:08.119134','2026-09-08 04:45:08.119171'),('82de6163-a755-4789-a39a-4ea38cdba0ab','7fba3954-cf27-4a01-979d-876f93179a37','b14c4923-e4c9-4a8b-874b-d43c1b5b1995','COMMIT_MESSAGE','SAGA-15','HIGH',NULL,'2026-09-08 04:45:04.487012','2026-09-08 04:45:04.487045'),('872ba5a6-e1c5-4a72-9964-cb0c87f25167','4826ac56-edd6-4db2-8ade-fef499ec9694','b044a74a-a8eb-4585-9bd7-f0073467519c','COMMIT_MESSAGE','SAGA-52','HIGH',NULL,'2026-09-08 04:45:09.519750','2026-09-08 04:45:09.519779'),('89667630-761d-470d-95f4-3da8bf55dcd2','bc4a2a38-f827-4a76-bf09-b9baee114fa5','55d7d490-4cd8-4c4e-aac6-c1805539f30e','RECONCILIATION','SAGA-43','HIGH',NULL,'2026-09-09 04:59:22.300757','2026-09-09 04:59:22.300772'),('8d71e0f2-3308-4f21-8d19-fe24c6c359f5','6b61bb34-ea6b-4ff8-9117-cc0db2c4a5be','8dcc9332-1952-4bb0-8771-dac029128176','COMMIT_MESSAGE','SAGA-45','HIGH',NULL,'2026-09-09 04:18:23.079061','2026-09-09 04:18:23.079079'),('8e8316f9-2dd2-428a-9d9f-749ad92c7da2','44b27758-e9fd-40c9-a5ff-c660f0495c52','131ca2a8-40f3-4138-ad8d-e46338496578','COMMIT_MESSAGE','SAGA-41','HIGH',NULL,'2026-09-08 04:45:05.425138','2026-09-08 04:45:05.425171'),('948525fc-ec93-4db5-9311-4f04da17408c','7fba3954-cf27-4a01-979d-876f93179a37','342f9426-dbeb-45f5-bd14-4ed8c3b53c97','COMMIT_MESSAGE','SAGA-15','HIGH',NULL,'2026-09-08 04:45:12.936575','2026-09-08 04:45:12.936590'),('97ca9a7a-94cd-4289-9d2a-a7ae60bea9e2','f3615567-e83e-481c-8c88-c1b6ff5419a6','fa893c43-ec4f-41c8-9d06-8df4c8c2507b','COMMIT_MESSAGE','SAGA-40','HIGH',NULL,'2026-09-08 04:45:03.299694','2026-09-08 04:45:03.299732'),('983d9e2c-a966-4bdd-9a48-7ecb2f910030','c924fa17-4cce-4d26-b9c1-f4c4acf1cc70','51d0b757-effb-49fd-968a-f6fe4aff4e47','COMMIT_MESSAGE','SAGA-4','HIGH',NULL,'2026-09-08 04:45:09.636969','2026-09-08 04:45:09.637001'),('9997c0af-c8b6-409b-b0f8-12ecdec059f8','e5cf67a9-a271-4757-97eb-ae98cbdef5b2','5f7396ea-8917-44b8-aa95-3450c062b952','COMMIT_MESSAGE','SAGA-38','HIGH',NULL,'2026-09-08 04:45:08.820769','2026-09-08 04:45:08.820805'),('99d202fb-ff82-41e5-9669-f7178476b7db','de4a1df8-fbf9-46ac-afbe-928a5863147e','f828ceea-117d-493a-bfb7-3f8e6924b3f3','COMMIT_MESSAGE','SAGA-51','HIGH',NULL,'2026-09-08 04:45:12.000508','2026-09-08 04:45:12.000522'),('9c9e802f-1468-41e1-afdb-583ac39ebf44','d211176d-b178-4cf2-a327-8f502819f3cb','ac2f47bb-f388-4df8-aa9c-d1ae60dd3bdd','COMMIT_MESSAGE','SAGA-1','HIGH',NULL,'2026-09-08 04:45:10.699517','2026-09-08 04:45:10.699665'),('a1f97bf2-efc0-4f5c-917c-eb0d1c1056f8','de4a1df8-fbf9-46ac-afbe-928a5863147e','db6817ae-9724-400e-aee7-b10c8c50145c','RECONCILIATION','SAGA-51','HIGH',NULL,'2026-09-08 13:13:42.235440','2026-09-08 13:13:42.235457'),('a2be936c-b3f9-49b9-98ae-727c2ff967d2','43042e09-a358-461c-a7f3-1e68ccd82e9a','6d1c0737-f827-42f4-a9a4-36683dd6d1cd','COMMIT_MESSAGE','SAGA-20','HIGH',NULL,'2026-09-08 04:45:06.834141','2026-09-08 04:45:06.834173'),('a7d3caa5-c946-4d51-a6c4-a116eb7ad117','de4a1df8-fbf9-46ac-afbe-928a5863147e','0ec95c1c-5e22-43d6-a2f9-e4f50709da56','RECONCILIATION','SAGA-51','HIGH',NULL,'2026-09-08 13:13:40.831961','2026-09-08 13:13:40.831975'),('a8b15427-c296-4d46-baee-58655a4d41a9','4826ac56-edd6-4db2-8ade-fef499ec9694','18850cbb-0c41-4735-b321-8889ffd99121','COMMIT_MESSAGE','SAGA-52','HIGH',NULL,'2026-09-08 08:54:04.407200','2026-09-08 08:54:04.407211'),('aa96346e-5e39-4cf1-a08b-4db9219b3106','f5e2984d-6d49-4c97-9b0d-806728ba95d5','e012b2dd-0ecc-40fc-b48e-91218e68d1e1','COMMIT_MESSAGE','SAGA-33','HIGH',NULL,'2026-09-08 04:45:10.935139','2026-09-08 04:45:10.935159'),('aac95e6e-47de-474b-80d5-b2c056ebbdb3','271bd33a-f319-4cf3-b992-5cdb8af9ad33','bbe045b3-b552-4946-9ada-25a16eb46c13','RECONCILIATION','SAGA-17','HIGH',NULL,'2026-09-08 04:45:05.075597','2026-09-08 04:45:05.075649'),('ac74c091-d3e0-4f6a-9441-363d2cd0fdfd','77bbc908-5999-414b-a803-059c13bd37fc','cdf22adc-3778-4b1d-826b-51665e6134fb','COMMIT_MESSAGE','SAGA-36','HIGH',NULL,'2026-09-08 04:45:11.060763','2026-09-08 04:45:11.060783'),('aca31b5d-d7b3-4591-895a-df3392498b61','bc4e5d61-bc47-40d0-b360-f9a972e8f0a7','1fc287ad-204e-4e1b-b6f2-406cd6bc0ed7','COMMIT_MESSAGE','SAGA-19','HIGH',NULL,'2026-09-08 04:45:09.988116','2026-09-08 04:45:09.988149'),('b1230db4-2e43-43a7-bb6f-157e24472313','f87c54b8-9e47-4a68-bb60-d8e225e4d52c','6d1c0737-f827-42f4-a9a4-36683dd6d1cd','COMMIT_MESSAGE','SAGA-21','HIGH',NULL,'2026-09-08 04:45:06.951680','2026-09-08 04:45:06.951711'),('bae537e3-515a-4d86-b6f2-383514764100','77bbc908-5999-414b-a803-059c13bd37fc','1db3aa2c-b653-4b70-bee4-d325ff0995d8','COMMIT_MESSAGE','SAGA-36','HIGH',NULL,'2026-09-08 04:45:06.482223','2026-09-08 04:45:06.482255'),('bc17cab4-157b-4872-ac47-2d136b28c368','3acc6c90-36bf-4d39-9d53-27dbfe53e5a8','e7b5f8f3-6375-4808-bd2f-0072f832ad21','COMMIT_MESSAGE','SAGA-37','HIGH',NULL,'2026-09-08 04:45:12.818712','2026-09-08 04:45:12.818725'),('bed364d7-2b62-42da-88b5-dd2d1b8d3a63','3a227480-782c-49cd-9989-d2a02a66a334','3bb5d7fd-1adc-40bb-873a-9f161c182c67','COMMIT_MESSAGE','SAGA-46','HIGH',NULL,'2026-09-08 04:45:13.172026','2026-09-08 04:45:13.172040'),('c04165c9-3595-4577-9bd9-74f432dfa861','19a08943-6375-4ae0-9f96-34aa1eb4ec47','35b6c8ae-b4d3-4c91-b61a-0efb44b8f39a','COMMIT_MESSAGE','SAGA-16','HIGH',NULL,'2026-09-08 04:45:04.958353','2026-09-08 04:45:04.958387'),('c5e257de-17cd-494e-ae09-0520a412c386','8f954a9d-85bb-44d9-ae43-49e3ac105a5e','ab478857-28e6-40f4-8309-6fac91e2f540','COMMIT_MESSAGE','SAGA-23','HIGH',NULL,'2026-09-08 04:45:03.066198','2026-09-08 04:45:03.066240'),('c7d18276-42bb-457a-a6d5-84a9de387ba5','756a5dcd-8540-4527-9cd0-d2af298dad56','72af8a52-5d7d-4ae3-bc46-9a4373199291','COMMIT_MESSAGE','SAGA-35','HIGH',NULL,'2026-09-08 04:45:12.465813','2026-09-08 04:45:12.465827'),('cc7c8fdf-ce7c-40cf-8df2-bb37209e0c69','e2f4f0ec-6eca-40d6-9623-f27a8527a7d5','a6ae5f5f-3f2f-4182-bbd3-38cf09bdfaf7','COMMIT_MESSAGE','SAGA-47','HIGH',NULL,'2026-09-08 04:45:03.534695','2026-09-08 04:45:03.534777'),('ce24b214-626f-4967-ac3c-6d86572d6847','7fba3954-cf27-4a01-979d-876f93179a37','48b1f9d0-6961-4add-8eaa-35c210ddab68','COMMIT_MESSAGE','SAGA-15','HIGH',NULL,'2026-09-08 04:45:02.241561','2026-09-08 04:45:02.241584'),('d33c39b1-efcc-4772-a6ba-181652a082a0','98af9398-cf00-4c84-ab53-b7b5ce2cfbb8','eddc3af2-4388-4160-b9e3-5cabdc95a509','COMMIT_MESSAGE','SAGA-30','HIGH',NULL,'2026-09-08 04:45:04.250387','2026-09-08 04:45:04.250918'),('d7781609-a5d2-41bd-b737-24c291b87dc0','4c7fa3c0-f5e4-43e0-8f0f-65e8d7fc2c2e','96da9506-c0f6-4cf2-88dd-ee1cdc8f55de','COMMIT_MESSAGE','SAGA-29','HIGH',NULL,'2026-09-08 04:45:09.403158','2026-09-08 04:45:09.403185'),('d95c64bd-f5e2-4a9f-9c4f-51f18a612ea4','4826ac56-edd6-4db2-8ade-fef499ec9694','db6817ae-9724-400e-aee7-b10c8c50145c','RECONCILIATION','SAGA-52','HIGH',NULL,'2026-09-08 08:54:04.873505','2026-09-08 08:54:04.873516'),('dadb8d65-865e-414d-b36d-7e56d932221b','4c91f20c-7d62-4e44-bf03-2112afe1795a','5eebf7c2-2224-40fb-87c7-e9720295b394','COMMIT_MESSAGE','SAGA-39','HIGH',NULL,'2026-09-08 04:45:08.584612','2026-09-08 04:45:08.584638'),('e5914435-af03-4493-9711-4760f99d551c','de4a1df8-fbf9-46ac-afbe-928a5863147e','cbfc39fa-66fc-42d5-b24f-4325507bedd5','COMMIT_MESSAGE','SAGA-51','HIGH',NULL,'2026-09-08 04:45:12.116863','2026-09-08 04:45:12.116878'),('e5bc075f-baf6-4521-8336-a3a95b56897e','d4c144a7-42a3-4311-8e54-63dad323dd92','081b14e7-c125-4afe-9b86-a8a078d7af5d','COMMIT_MESSAGE','SAGA-18','HIGH',NULL,'2026-09-08 04:45:03.767918','2026-09-08 04:45:03.767958'),('eb0077e2-8961-4817-b087-4f376266a025','759d6fe7-4859-41b1-b809-008027ef2e6a','776f29f8-91f6-48c9-940c-f79610ec3406','COMMIT_MESSAGE','SAGA-2','HIGH',NULL,'2026-09-08 04:45:10.818208','2026-09-08 04:45:10.818266'),('eb816863-9e1b-4464-8a7f-895ff2a140a9','4826ac56-edd6-4db2-8ade-fef499ec9694','38414731-1a6a-4fd4-8438-616740d55c86','RECONCILIATION','SAGA-52','HIGH',NULL,'2026-09-08 08:54:04.174363','2026-09-08 08:54:04.174374'),('ed5e261e-8e67-476b-a36e-5aa7b188bd5f','759d6fe7-4859-41b1-b809-008027ef2e6a','ddea212b-2a20-4cba-853d-4c49f8c0af8b','COMMIT_MESSAGE','SAGA-2','HIGH',NULL,'2026-09-08 04:45:11.767949','2026-09-08 04:45:11.767964'),('f194a4af-3e7f-4228-b8ec-c07a0c015279','de4a1df8-fbf9-46ac-afbe-928a5863147e','55d7d490-4cd8-4c4e-aac6-c1805539f30e','COMMIT_MESSAGE','SAGA-51','HIGH',NULL,'2026-09-08 14:01:46.817894','2026-09-08 14:01:46.817914'),('f432477d-aaf5-46e4-8e30-60af77de5891','3acc6c90-36bf-4d39-9d53-27dbfe53e5a8','036a8ec8-6a67-4555-b573-4718394d0ae9','COMMIT_MESSAGE','SAGA-37','HIGH',NULL,'2026-09-08 04:45:11.419459','2026-09-08 04:45:11.419475'),('f564431a-7191-441b-903f-7673f595e3e2','6f5545bc-997a-4fd5-a342-331c1afdaafe','f410e5c8-7657-4f5a-b4f2-890c60cf2021','COMMIT_MESSAGE','SAGA-31','HIGH',NULL,'2026-09-08 04:45:09.053664','2026-09-08 04:45:09.053691'),('f95305d0-2143-40cd-b40c-74d8095dbe4d','bc4a2a38-f827-4a76-bf09-b9baee114fa5','d19c31a0-71ea-472e-ae16-1719444c517c','COMMIT_MESSAGE','SAGA-43','HIGH',NULL,'2026-09-08 04:45:09.286330','2026-09-08 04:45:09.286365'),('f9ccd138-b937-498b-b2b1-5bb188968577','de4a1df8-fbf9-46ac-afbe-928a5863147e','40c03b1c-18e5-4d36-bd17-4f91625443fa','RECONCILIATION','SAGA-51','HIGH',NULL,'2026-09-08 13:13:41.300383','2026-09-08 13:13:41.300402'),('fa1cf48c-b4ec-41ba-8c98-0fc83cf61627','de4a1df8-fbf9-46ac-afbe-928a5863147e','38414731-1a6a-4fd4-8438-616740d55c86','RECONCILIATION','SAGA-51','HIGH',NULL,'2026-09-08 13:13:41.066476','2026-09-08 13:13:41.066497'),('fd2c30b2-84b1-49c4-92c1-7a9784157c95','ad09ea88-a9ea-4354-9dbc-90963a2ab10b','db6817ae-9724-400e-aee7-b10c8c50145c','COMMIT_MESSAGE','SAGA-53','HIGH',NULL,'2026-09-08 04:45:03.651228','2026-09-08 04:45:03.651301'),('fddd6a62-4d93-4d68-a416-52f7f280d778','de4a1df8-fbf9-46ac-afbe-928a5863147e','18850cbb-0c41-4735-b321-8889ffd99121','RECONCILIATION','SAGA-51','HIGH',NULL,'2026-09-08 13:13:41.767919','2026-09-08 13:13:41.767938'),('fe9d4510-d329-4639-807a-c3c716f8b135','f5e2984d-6d49-4c97-9b0d-806728ba95d5','18207264-34df-4f97-b0c5-10ea776dafab','COMMIT_MESSAGE','SAGA-33','HIGH',NULL,'2026-09-08 04:45:11.883986','2026-09-08 04:45:11.884001'),('ffa7c9b9-d871-4a28-98fb-85fbbc7f1bb5','4c7fa3c0-f5e4-43e0-8f0f-65e8d7fc2c2e','7fc52623-326b-45b3-a5b3-2e487170da9f','COMMIT_MESSAGE','SAGA-29','HIGH',NULL,'2026-09-08 04:45:06.129628','2026-09-08 04:45:06.129664');
/*!40000 ALTER TABLE `task_git_commit_link` ENABLE KEYS */;
UNLOCK TABLES;
SET @@SESSION.SQL_LOG_BIN = @MYSQLDUMP_TEMP_LOG_BIN;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-09-13 19:59:01
