package codeRecipe.crawling.crawling;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AuthController {

    @GetMapping("/login")
    public String login(
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "logout", required = false) String logout,
            Model model) {
        
        if (error != null) {
            model.addAttribute("errorMessage", "아이디 또는 비밀번호가 올바르지 않습니다.");
        }
        
        if (logout != null) {
            model.addAttribute("successMessage", "성공적으로 로그아웃되었습니다.");
        }
        
        return "auth/login";
    }

    @GetMapping("/")
    public String home() {
        return "redirect:/admin";
    }
}