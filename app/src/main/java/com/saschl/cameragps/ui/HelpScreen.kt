package com.saschl.cameragps.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.saschl.cameragps.R

private data class FaqItem(
    val questionRes: Int,
    val answerRes: Int,
    val containsLink: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(
    onBackClick: () -> Unit
) {
    val faqItems = listOf(
        FaqItem(
            questionRes = R.string.is_there_documenation,
            answerRes = R.string.is_there_documenation_answer,
            containsLink = true
        ),
        FaqItem(
            questionRes = R.string.how_about_privacy,
            answerRes = R.string.how_about_privacy_answer,
        ),
        FaqItem(
            questionRes = R.string.faq_enable_service_question,
            answerRes = R.string.faq_enable_service_answer
        ),
        FaqItem(
            questionRes = R.string.faq_connect_camera_question,
            answerRes = R.string.faq_connect_camera_answer
        ),
        FaqItem(
            questionRes = R.string.faq_camera_not_appearing_question,
            answerRes = R.string.faq_camera_not_appearing_answer
        ),
        FaqItem(
            questionRes = R.string.faq_camera_not_connecting_question,
            answerRes = R.string.faq_camera_not_connecting_answer
        ),
        FaqItem(
            questionRes = R.string.faq_permissions_question,
            answerRes = R.string.faq_permissions_answer
        ),
        FaqItem(
            questionRes = R.string.faq_gps_accuracy_question,
            answerRes = R.string.faq_gps_accuracy_answer
        ),
        FaqItem(
            questionRes = R.string.faq_background_work_question,
            answerRes = R.string.faq_background_work_answer
        ),
        FaqItem(
            questionRes = R.string.faq_disconnects_question,
            answerRes = R.string.faq_disconnects_answer
        )
    )

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.help_faq_title),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            painterResource(R.drawable.arrow_back_24px),
                            contentDescription = stringResource(R.string.help_close_description)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // App description
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.help_about_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.help_about_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // FAQ items
            items(faqItems) { faq ->
                var expanded by remember { mutableStateOf(false) }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = stringResource(faq.questionRes),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (expanded) {
                            Spacer(modifier = Modifier.height(8.dp))
                            if (faq.containsLink) {
                                Text(
                                    buildAnnotatedString {
                                        append(stringResource(faq.answerRes))
                                        append("\n")
                                        withLink(
                                            LinkAnnotation.Url(
                                                "https://github.com/Saschl/camera-gps/blob/main/README.md",
                                                TextLinkStyles(
                                                    style = SpanStyle(
                                                        color = MaterialTheme.colorScheme.primary,
                                                        textDecoration = TextDecoration.Underline
                                                    )
                                                )
                                            )
                                        ) {
                                            append("https://github.com/Saschl/camera-gps")
                                        }
                                        append("\n")
                                        withLink(
                                            LinkAnnotation.Url(
                                                "mailto:saschl.ra@web.de",
                                                TextLinkStyles(
                                                    style = SpanStyle(
                                                        color = MaterialTheme.colorScheme.primary,
                                                        textDecoration = TextDecoration.Underline
                                                    )
                                                )
                                            )
                                        ) {
                                            append("saschl.ra@web.de")
                                        }
                                    }
                                )
                            } else {
                                Text(
                                    text = stringResource(faq.answerRes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Footer
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.help_need_more_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.help_need_more_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }
    }
}

