package com.example.qquickqqueue.domain.members.service;

import com.example.qquickqqueue.domain.enumPackage.Gender;
import com.example.qquickqqueue.domain.members.dto.request.KakaoMemberInfoDto;
import com.example.qquickqqueue.domain.members.entity.Members;
import com.example.qquickqqueue.domain.members.repository.MembersRepository;
import com.example.qquickqqueue.redis.util.RedisUtil;
import com.example.qquickqqueue.security.jwt.JwtUtil;
import com.example.qquickqqueue.security.jwt.TokenDto;
import com.example.qquickqqueue.util.Message;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

import static com.example.qquickqqueue.security.jwt.JwtUtil.ACCESS_KEY;
import static com.example.qquickqqueue.security.jwt.JwtUtil.REFRESH_KEY;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class KakaoMembersService {
    @Value("${kakao.api.key}")
    private String kakaoApiKey;
    @Value("${kakao.client.id}")
    private String client_id;
    @Value("${kakao.client.secret}")
    private String client_secret;
    @Value("${kakao.redirect.uri}")
    private String redirect_uri;

    private final MembersRepository membersRepository;
    private final RedisUtil redisUtil;
    private final JwtUtil jwtUtil;

    public ResponseEntity<Message> kakaoLogin(String code, HttpServletResponse response) throws JsonProcessingException {
//        String accessToken = getToken(code);
        KakaoMemberInfoDto kakaoMemberInfoDto = getKakaoMemeberInfo(code);

        Members member = registerKakaoMemberIfNeeded(kakaoMemberInfoDto);
        TokenDto tokenDto = jwtUtil.createAllToken(member);

        String refreshToken = tokenDto.getRefreshToken();
        redisUtil.set(member.getEmail(), refreshToken, Duration.ofDays(7).toMillis());
        response.addHeader(ACCESS_KEY, tokenDto.getAccessToken());
        response.addHeader(REFRESH_KEY, tokenDto.getRefreshToken());
        return new ResponseEntity<>(new Message("로그인 성공", null), HttpStatus.OK);
    }

    private String getToken(String code) throws JsonProcessingException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("client_id", client_id);
        body.add("client_secret", client_secret);
        body.add("redirect_uri", redirect_uri);
        body.add("code", code);

        HttpEntity<MultiValueMap<String, String>> kakaoTokenRequest = new HttpEntity<>(body, headers);
        RestTemplate rt = new RestTemplate();
        ResponseEntity<String> response = rt.postForEntity(
                "https://kauth.kakao.com/oauth/token",
                kakaoTokenRequest,
                String.class
        );

        if (response.getStatusCode() != HttpStatus.OK) {
            throw new RestClientException("카카오 서버가 원활하지 않음. Status : " + response.getStatusCode());
        }

        String responseBody = response.getBody();
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = objectMapper.readTree(responseBody);
        return jsonNode.get("access_token").asText();
    }

    private KakaoMemberInfoDto getKakaoMemeberInfo(String accessToken) throws JsonProcessingException {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer " + accessToken);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        HttpEntity<MultiValueMap<String, String>> kakaoUserInfoRequest = new HttpEntity<>(headers);
        RestTemplate rt = new RestTemplate();
        ResponseEntity<String> response = rt.exchange(
                "https://kapi.kakao.com/v2/user/me",
                HttpMethod.GET,
                kakaoUserInfoRequest,
                String.class
        );

        if (response.getStatusCode() != HttpStatus.OK) {
            throw new RestClientException("카카오 서버가 원활하지 않음. Status : " + response.getStatusCode());
        }

        String responseBody = response.getBody();
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = objectMapper.readTree(responseBody);

        String phoneNumber = jsonNode.get("kakao_account").get("phone_number").asText();

        if (!phoneNumber.startsWith("+82 ")) {
            throw new RuntimeException("한국 번호로만 가입해줘~~");
        } else {
            phoneNumber = "0" + phoneNumber.substring(4);
        }

        return KakaoMemberInfoDto.builder()
                .name(jsonNode.get("properties").get("nickname").asText())
                .email(jsonNode.get("kakao_account").get("email").asText())
                .gender(Gender.valueOf(jsonNode.get("kakao_account").get("gender").asText().toUpperCase()))
                .birth(LocalDate.parse(jsonNode.get("kakao_account").get("birthyear").asText() + jsonNode.get("kakao_account").get("birthday").asText(),
                        DateTimeFormatter.ofPattern("yyyyMMdd")))
                .phoneNumber(phoneNumber)
                .build();
    }

    private Members registerKakaoMemberIfNeeded(KakaoMemberInfoDto kakaoMemberInfoDto) {
        Optional<Members> member = membersRepository.findByEmail(kakaoMemberInfoDto.getEmail());
        Members fMember;
        if (member.isEmpty()) {
            fMember = Members.builder()
                    .email(kakaoMemberInfoDto.getEmail())
                    .password(UUID.randomUUID().toString())
                    .gender(kakaoMemberInfoDto.getGender())
                    .name(kakaoMemberInfoDto.getName())
                    .birth(kakaoMemberInfoDto.getBirth())
                    .phoneNumber(kakaoMemberInfoDto.getPhoneNumber())
                    .isKakaoEmail(true)
                    .build();
            membersRepository.save(fMember);
        } else {
            fMember = member.get();
            if (!fMember.isKakaoEmail()) {
                fMember.setIsKakaoEmail();
                membersRepository.save(fMember);
            }
        }
        return fMember;
    }

    public ResponseEntity<Message> kakaoWithdrawal(String code, HttpServletResponse response, Members member) throws JsonProcessingException {
        String accessToken = getToken(code);

        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer " + accessToken);

        HttpEntity<String> requestEntity = new HttpEntity<>("", headers);

        RestTemplate rt = new RestTemplate();
        ResponseEntity<String> responseEntity = rt.exchange(
            "https://kapi.kakao.com/v1/user/unlink",
            HttpMethod.POST, requestEntity, String.class);

        if (responseEntity.getStatusCode().is2xxSuccessful()) {
            member.updateDate();
            membersRepository.save(member);
            return new ResponseEntity<>(new Message("카카오 탈퇴 성공", null), HttpStatus.OK);
        } else {
            throw new HttpClientErrorException(responseEntity.getStatusCode());
        }
    }
}