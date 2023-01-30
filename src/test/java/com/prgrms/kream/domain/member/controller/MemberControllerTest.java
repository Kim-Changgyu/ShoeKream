package com.prgrms.kream.domain.member.controller;

import static com.prgrms.kream.domain.image.model.DomainType.*;
import static com.prgrms.kream.domain.member.model.Authority.*;
import static org.mockito.Mockito.*;
import static org.springframework.http.MediaType.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.InputStream;
import java.net.URL;
import java.util.List;

import javax.servlet.http.Cookie;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prgrms.kream.MysqlTestContainer;
import com.prgrms.kream.domain.image.model.Image;
import com.prgrms.kream.domain.image.repository.ImageRepository;
import com.prgrms.kream.domain.member.dto.request.MemberLoginRequest;
import com.prgrms.kream.domain.member.dto.request.MemberRegisterRequest;
import com.prgrms.kream.domain.member.model.Member;
import com.prgrms.kream.domain.member.repository.MemberRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemberControllerTest extends MysqlTestContainer {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	AmazonS3 amazonS3;

	private final String bucket = "s3test";

	@Value("${jwt.accessToken}")
	private String accessToken;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private ImageRepository imageRepository;

	static Long memberId;

	@BeforeEach
	void setUp() {
		Member member = memberRepository.save(
				Member.builder()
						.email("hello@naver.com")
						.name("name")
						.password("Pa!12345678")
						.phone("01012345678")
						.isMale(true)
						.authority(ROLE_USER)
						.build());

		memberId = member.getId();

		imageRepository.save(
				Image.builder()
						.referenceId(memberId)
						.domainType(MEMBER)
						.fullPath("/path/test1")
						.originalName("profile1")
						.build()
		);

		SecurityContext context = SecurityContextHolder.getContext();
		UsernamePasswordAuthenticationToken usernamePasswordAuthenticationToken =
				new UsernamePasswordAuthenticationToken(
						memberId, null, List.of(new SimpleGrantedAuthority(ROLE_USER.name()))
				);

		context.setAuthentication(usernamePasswordAuthenticationToken);
	}

	@AfterEach
	void tearDown() {
		memberRepository.deleteAll();
	}

	@Test
	@DisplayName("회원가입 - 성공")
	void register_success() throws Exception {
		MemberRegisterRequest memberRegisterRequest = new MemberRegisterRequest(
				"name", "email@naver.com", "01012345678", "aA12345678!", true, ROLE_USER);

		mockMvc.perform(post("/api/v1/member/signup")
						.contentType(APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(memberRegisterRequest))
				).andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.id").value(memberId + 1))
				.andDo(print())
		;
	}

	@Test
	@DisplayName("로그인 - 성공")
	void login_success() throws Exception {
		MemberLoginRequest memberLoginRequest = new MemberLoginRequest(
				"hello@naver.com", "Pa!12345678"
		);

		mockMvc.perform(post("/api/v1/member/login")
						.contentType(APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(memberLoginRequest))
				).andExpect(status().isOk())
				.andExpect(header().exists("Set-Cookie"))
				.andDo(print())
				.andReturn();
	}

	@Test
	@DisplayName("로그아웃 - 성공")
	void logout_success() throws Exception {
		MemberLoginRequest memberLoginRequest = new MemberLoginRequest(
				"hello@naver.com", "Pa!12345678"
		);

		MvcResult mvcResult = mockMvc.perform(post("/api/v1/member/login")
						.contentType(APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(memberLoginRequest))
				).andExpect(status().isOk())
				.andReturn();

		Cookie tokenCookie = mvcResult.getResponse().getCookie(accessToken);

		mockMvc.perform(get("/api/v1/member/logout").cookie(tokenCookie))
				.andExpect(status().isOk())
				.andExpect(header().string("Set-Cookie", Matchers.startsWith(accessToken + "=; Max-Age=0;")))
				.andDo(print());
	}

	@Test
	@DisplayName("사용자 정보 조회 성공")
	void get_success() throws Exception {
		mockMvc.perform(get("/api/v1/member/{id}", memberId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.id").value(memberId))
				.andExpect(jsonPath("$.data.name").value("name"))
				.andExpect(jsonPath("$.data.email").value("hello@naver.com"))
				.andExpect(jsonPath("$.data.phone").value("01012345678"))
				.andExpect(jsonPath("$.data.imagePaths[0]").value("/path/test1"));
	}

	@Test
	@DisplayName("사용자 정보 수정 성공")
	void update_success() throws Exception {
		MockMultipartFile mockImage = new MockMultipartFile(
				"imageFile",
				"test.png",
				IMAGE_PNG_VALUE,
				"imageFile".getBytes()
		);

		when(amazonS3.getUrl(eq(bucket), anyString()))
				.thenReturn(new URL("http://testURL"));

		mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/member/{memberId}", memberId)
						.file(mockImage)
						.param("name", "updatedName")
						.param("phone", "01023456789")
						.param("password", "changed!12345")
				)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.id").value(memberId))
				.andExpect(jsonPath("$.data.name").value("updatedName"))
				.andExpect(jsonPath("$.data.phone").value("01023456789"))
				.andExpect(jsonPath("$.data.imagePaths[0]").value("http://testURL"))
				.andDo(print());

		verify(amazonS3, times(1)).putObject(eq(bucket), anyString(), any(InputStream.class), any(ObjectMetadata.class));
		verify(amazonS3, times(1)).getUrl(eq(bucket), anyString());
	}
}